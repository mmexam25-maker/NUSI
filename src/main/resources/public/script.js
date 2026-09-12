let sliderImages = [];
let sliderIndex = 0;
let sliderTimer = null;

const $ = id => document.getElementById(id);
const val = id => ($(id)?.value || '').trim();

window.addEventListener('load', () => {
  loadSlider();

  ['surname', 'givenName', 'city'].forEach(id => {
    $(id)?.addEventListener('input', function () {
      this.value = toTitleCase(this.value);
    });
  });

  $('dob')?.addEventListener('change', calculateAge);

  $('memberForm')?.addEventListener('submit', e => {
    e.preventDefault();
    submitForm();
  });
});

function toTitleCase(str) {
  return String(str || '')
    .toLowerCase()
    .replace(/\b\w/g, c => c.toUpperCase());
}

async function loadSlider() {
  const box = $('sliderImages');
  if (!box) return;

  try {
    const r = await fetch('/api/slider', { cache: 'no-store' });
    const j = await r.json();

    if (!r.ok) {
      throw new Error(j.error || 'Unable to load slider.');
    }

    sliderImages = Array.isArray(j) ? j : [];

    if (!sliderImages.length) {
      box.innerHTML = '<div class="slider-empty">No updates available.</div>';
      return;
    }

    box.innerHTML = `
      <div class="slider-stage">
        <img id="sliderMainPhoto" alt="NUSI update">
        <button type="button" class="slider-arrow prev" aria-label="Previous">❮</button>
        <button type="button" class="slider-arrow next" aria-label="Next">❯</button>
        <div id="sliderDots" class="slider-dots"></div>
      </div>`;

    box.querySelector('.prev').addEventListener('click', () => moveSlider(-1));
    box.querySelector('.next').addEventListener('click', () => moveSlider(1));

    const dots = $('sliderDots');

    sliderImages.forEach((_, i) => {
      const d = document.createElement('span');
      d.className = 'slider-dot';
      d.addEventListener('click', () => {
        sliderIndex = i;
        showSlide();
        restartSlider();
      });
      dots.appendChild(d);
    });

    showSlide();
    startSlider();

  } catch (e) {
    box.innerHTML = '<div class="slider-empty">Unable to load updates.</div>';
    console.error(e);
  }
}

function showSlide() {
  if (!sliderImages.length) return;

  const img = $('sliderMainPhoto');
  if (!img) return;

  img.src = sliderImages[sliderIndex].url;
  img.alt = sliderImages[sliderIndex].name || 'NUSI update';

  document.querySelectorAll('.slider-dot').forEach((d, i) => {
    d.classList.toggle('active', i === sliderIndex);
  });
}

function moveSlider(step) {
  if (!sliderImages.length) return;
  sliderIndex = (sliderIndex + step + sliderImages.length) % sliderImages.length;
  showSlide();
  restartSlider();
}

function startSlider() {
  clearInterval(sliderTimer);

  if (sliderImages.length > 1) {
    sliderTimer = setInterval(() => {
      sliderIndex = (sliderIndex + 1) % sliderImages.length;
      showSlide();
    }, 3000);
  }
}

function restartSlider() {
  clearInterval(sliderTimer);
  startSlider();
}

function calculateAge() {
  const dob = val('dob');
  if (!dob) return;

  const birth = new Date(dob + 'T00:00:00');
  if (Number.isNaN(birth.getTime())) return;

  const today = new Date();
  let age = today.getFullYear() - birth.getFullYear();
  const m = today.getMonth() - birth.getMonth();

  if (m < 0 || (m === 0 && today.getDate() < birth.getDate())) {
    age--;
  }

  $('age').value = age;

  const s = $('eligibility');
  if (age >= 18 && age <= 60) {
    s.textContent = '✅ Eligible';
    s.style.color = '#198754';
  } else {
    s.textContent = '❌ Not Eligible';
    s.style.color = '#dc3545';
  }
}

async function submitForm() {
  const age = parseInt(val('age'), 10);

  if (!Number.isFinite(age) || age < 18 || age > 60) {
    showToast('Not Eligible. Age should be between 18 and 60 years.', true);
    return;
  }

  const photoFile = $('photo').files[0];
  const cdcFile = $('cdcfile').files[0];
  const passportFile = $('passport').files[0];

  if (!photoFile || !cdcFile || !passportFile) {
    showToast('Please attach Photo, CDC and Passport.', true);
    return;
  }

  if (!['image/jpeg', 'image/png'].includes(photoFile.type)) {
    showToast('Photo must be JPG/JPEG or PNG.', true);
    return;
  }

  if (cdcFile.type !== 'application/pdf' || passportFile.type !== 'application/pdf') {
    showToast('CDC and Passport must be PDF files.', true);
    return;
  }

  setLoading(true);

  try {
    const data = {
      surname: val('surname'),
      givenName: val('givenName'),
      cdc: val('cdc').toUpperCase(),
      indos: val('indos').toUpperCase(),
      dob: val('dob'),
      age: val('age'),
      blood: val('blood'),
      nominee: val('nominee'),
      rank: val('rank'),
      mobile: val('mobile'),
      altmobile: val('altmobile'),
      email: val('email'),
      altemail: val('altemail'),
      address1: val('address1'),
      address2: val('address2'),
      address3: val('address3'),
      city: val('city'),
      state: val('state'),
      pincode: val('pincode'),
      photo: await fileToBase64(photoFile),
      cdcfile: await fileToBase64(cdcFile),
      passport: await fileToBase64(passportFile)
    };

    const r = await fetch('/api/submit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    });

    const res = await r.json();

    if (!r.ok || !res.success) {
      throw new Error(res.message || res.error || 'Membership submission failed.');
    }

    showToast('Membership Submitted Successfully. Temporary links are valid for 24 hours.');
    whatsapp(res);

    $('memberForm').reset();
    $('eligibility').textContent = '';

  } catch (e) {
    showToast(e.message || String(e), true);

  } finally {
    setLoading(false);
  }
}

function setLoading(on) {
  $('loading').hidden = !on;
  $('submitBtn').disabled = on;
}

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();

    reader.onload = e => {
      const raw = String(e.target.result || '');
      resolve({
        name: file.name,
        mime: file.type,
        data: raw.includes(',') ? raw.split(',')[1] : raw
      });
    };

    reader.onerror = () => reject(new Error('File could not be read.'));
    reader.readAsDataURL(file);
  });
}

function whatsapp(d) {
const number = '919488352735';

  const msg = `*NUSI NEW MEMBERSHIP 2026*

*Surname:* ${d.surname || ''}
*Given Name:* ${d.givenName || ''}
*CDC No:* ${d.cdc || ''}
*INDoS No:* ${d.indos || ''}
*DOB:* ${d.dob || ''}
*Age:* ${d.age || ''}
*Blood Group:* ${d.blood || ''}
*Rank:* ${d.rank || ''}
*Nominee:* ${d.nominee || ''}

*Mobile:* ${d.mobile || ''}
*Alternate Mobile:* ${d.altmobile || ''}

*Email:* ${d.email || ''}
*Alternate Email:* ${d.altemail || ''}

*Address:* ${d.address || ''}

*Photo - temporary link*
${d.photo || ''}

*CDC PDF - temporary link*
${d.cdcfile || ''}

*Passport PDF - temporary link*
${d.passport || ''}

*Links valid until:* ${d.expiresAt || ''}`;

  window.open(
    'https://wa.me/' + number + '?text=' + encodeURIComponent(msg),
    '_blank'
  );
}

function showToast(message, isError = false) {
  const old = document.querySelector('.toast');
  if (old) old.remove();

  const t = document.createElement('div');
  t.className = 'toast ' + (isError ? 'error' : 'success');
  t.textContent = message;
  document.body.appendChild(t);

  setTimeout(() => t.remove(), 5000);
}
