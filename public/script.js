const WHATSAPP_NUMBER = "917538848180";

let fetchedMember = null;
let allMembers = [];

document.addEventListener("DOMContentLoaded", () => {
  loadSlider();
  bindTabs();
  bindMemberType();
  bindNominee();

  document.getElementById("name").addEventListener("input", function () {
    this.value = toTitleCase(this.value);
  });

  document.getElementById("dob").addEventListener("change", calculateAge);
  document.getElementById("memberForm").addEventListener("submit", submitForm);
  document.getElementById("fetchExistingBtn").addEventListener("click", () => {
    fetchExistingDetails(value("existingIndos"));
  });
  document.getElementById("inlineFetchBtn").addEventListener("click", () => {
    fetchExistingDetails(value("indos"));
  });
  document.getElementById("existingIndos").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      fetchExistingDetails(value("existingIndos"));
    }
  });
  document.getElementById("refreshMembersBtn").addEventListener("click", loadMembers);
  document.getElementById("memberSearch").addEventListener("input", renderMembers);

  document.getElementById("closeModalBtn").addEventListener("click", closeMemberModal);
  document.getElementById("modalCloseBottomBtn").addEventListener("click", closeMemberModal);
  document.getElementById("useMemberBtn").addEventListener("click", () => {
    if (fetchedMember) useExistingMember(fetchedMember);
  });
  document.getElementById("memberModal").addEventListener("click", (e) => {
    if (e.target.id === "memberModal") closeMemberModal();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") closeMemberModal();
  });

  setNomineeUI();
  setMemberTypeUI();
});

function toTitleCase(str) {
  return str.toLowerCase().replace(/\b\w/g, (l) => l.toUpperCase());
}

function bindTabs() {
  document.querySelectorAll(".tab-btn").forEach((button) => {
    button.addEventListener("click", () => switchTab(button.dataset.tab));
  });
}

function switchTab(tabId) {
  document.querySelectorAll(".tab-btn").forEach((button) => {
    button.classList.toggle("active", button.dataset.tab === tabId);
  });
  document.querySelectorAll(".tab-panel").forEach((panel) => {
    panel.classList.toggle("active", panel.id === tabId);
  });

  if (tabId === "membersTab") loadMembers();
  window.scrollTo({ top: 0, behavior: "smooth" });
}

function bindMemberType() {
  document.querySelectorAll('input[name="memberType"]').forEach((radio) => {
    radio.addEventListener("change", setMemberTypeUI);
  });
}

function bindNominee() {
  document.querySelectorAll('input[name="nominee"]').forEach((radio) => {
    radio.addEventListener("change", setNomineeUI);
  });
}

function updateRadioCards(groupSelector) {
  document.querySelectorAll(`${groupSelector} .radio-card`).forEach((label) => {
    const input = label.querySelector("input[type=radio]");
    label.classList.toggle("selected", !!input?.checked);
  });
}

function setMemberTypeUI() {
  const type = checkedValue("memberType") || "New Member";
  const existing = type === "Existing";

  document.getElementById("existingSearchBox").hidden = !existing;
  document.getElementById("inlineFetchBtn").hidden = !existing;
  document.getElementById("existingFilesNote").hidden = !existing;

  ["passport", "photo", "cdcfile", "sign"].forEach((id) => {
    document.getElementById(id).required = !existing;
  });

  document.querySelectorAll(".required-new").forEach((el) => {
    el.style.display = existing ? "none" : "inline";
  });

  document.getElementById("submitBtn").textContent = existing
    ? "Submit Existing / New Card"
    : "Submit Membership";

  updateRadioCards(".member-type-group");
}

function setNomineeUI() {
  const nominee = checkedValue("nominee") || "Spouse";
  const spouse = nominee === "Spouse";
  const row = document.getElementById("spousePhoneRow");
  const input = document.getElementById("spousePhone");

  row.hidden = !spouse;
  input.required = spouse;
  if (!spouse) input.value = "";

  updateRadioCards(".nominee-group");
}

async function loadSlider() {
  try {
    const response = await fetch("/api/slider");
    if (!response.ok) throw new Error("Slider failed");
    const images = await response.json();

    document.getElementById("sliderImages").innerHTML = images
      .map((img) => `<img src="${img.url}" alt="${escapeHtml(img.name)}" loading="lazy">`)
      .join("");
  } catch (err) {
    console.error(err);
    document.getElementById("sliderImages").innerHTML =
      '<div class="slider-empty">Updates will appear here.</div>';
  }
}

async function loadMembers() {
  const loading = document.getElementById("membersLoading");
  const table = document.getElementById("membersTable");
  loading.hidden = false;
  loading.textContent = "Loading members...";
  table.hidden = true;

  try {
    const response = await fetch("/api/members");
    const result = await response.json().catch(() => []);
    if (!response.ok) throw new Error(result.error || "Unable to load members.");

    allMembers = result;
    renderMembers();
    table.hidden = false;
    loading.hidden = true;
  } catch (err) {
    loading.hidden = false;
    loading.textContent = err.message || "Unable to load members.";
  }
}

function renderMembers() {
  const query = value("memberSearch").toLowerCase();
  const tbody = document.getElementById("membersBody");
  const loading = document.getElementById("membersLoading");
  const table = document.getElementById("membersTable");

  const filtered = allMembers.filter((member) => {
    if (!query) return true;
    return `${member.name} ${member.indos} ${member.memberType}`.toLowerCase().includes(query);
  });

  if (!filtered.length) {
    tbody.innerHTML = "";
    table.hidden = true;
    loading.hidden = false;
    loading.textContent = query ? "No matching members." : "No members found.";
    return;
  }

  loading.hidden = true;
  table.hidden = false;
  tbody.innerHTML = filtered.map((member) => `
    <tr>
      <td data-label="Sl. No.">${escapeHtml(member.slNo)}</td>
      <td data-label="Name">
        <strong>${escapeHtml(member.name)}</strong>
        <span class="indos-small">${escapeHtml(member.indos)}</span>
      </td>
      <td data-label="Member Type">
        <span class="type-badge ${String(member.memberType).toLowerCase().includes("new") ? "new" : "existing"}">
          ${escapeHtml(member.memberType || "Existing")}
        </span>
      </td>
      <td data-label="Action">
        <button type="button" class="new-card-btn" data-indos="${escapeHtml(member.indos)}">New Card</button>
      </td>
    </tr>
  `).join("");

  tbody.querySelectorAll(".new-card-btn").forEach((button) => {
    button.addEventListener("click", () => fetchExistingDetails(button.dataset.indos));
  });
}

async function fetchExistingDetails(indos) {
  const clean = String(indos || "").trim().toUpperCase();
  if (!/^\d{2}[A-Z]{2}\d{4}$/.test(clean)) {
    showStatus("Enter a valid INDoS No. Example: 12AB3456", false);
    return;
  }

  const fetchButton = document.getElementById("fetchExistingBtn");
  const inlineButton = document.getElementById("inlineFetchBtn");
  const oldFetchText = fetchButton.textContent;
  const oldInlineText = inlineButton.textContent;
  fetchButton.disabled = true;
  inlineButton.disabled = true;
  fetchButton.textContent = "Fetching...";
  inlineButton.textContent = "...";

  try {
    const response = await fetch(`/api/member/${encodeURIComponent(clean)}`);
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || "Member not found.");

    fetchedMember = result;
    document.getElementById("existingIndos").value = clean;
    document.getElementById("indos").value = clean;
    openMemberModal(result);
  } catch (err) {
    showStatus(err.message || "Member not found.", false);
  } finally {
    fetchButton.disabled = false;
    inlineButton.disabled = false;
    fetchButton.textContent = oldFetchText;
    inlineButton.textContent = oldInlineText;
  }
}

function openMemberModal(member) {
  const details = [
    ["Name", member.name],
    ["INDoS No.", member.indos],
    ["CDC No.", member.cdc],
    ["SID No.", member.sid],
    ["DOB", member.dob],
    ["Age", member.age],
    ["Blood Group", member.blood],
    ["Rank", member.rank],
    ["Nominee", member.nominee],
    ["Spouse Phone", member.spousePhone],
    ["Mobile", member.mobile],
    ["Email", member.email],
    ["Address", member.fullAddress],
    ["Membership", member.memberType || "Existing"]
  ];

  document.getElementById("modalTitle").textContent = member.name || "Member Details";
  document.getElementById("modalDetails").innerHTML = details
    .filter(([, val]) => String(val || "").trim())
    .map(([label, val]) => `
      <div class="detail-item">
        <span>${escapeHtml(label)}</span>
        <strong>${escapeHtml(val)}</strong>
      </div>
    `).join("");

  const modal = document.getElementById("memberModal");
  modal.hidden = false;
  document.body.classList.add("modal-open");
}

function closeMemberModal() {
  const modal = document.getElementById("memberModal");
  if (modal.hidden) return;
  modal.hidden = true;
  document.body.classList.remove("modal-open");
}

function useExistingMember(member) {
  const existingRadio = document.querySelector('input[name="memberType"][value="Existing"]');
  existingRadio.checked = true;
  setMemberTypeUI();

  setValue("name", member.name);
  setValue("cdc", member.cdc);
  setValue("indos", member.indos);
  setValue("existingIndos", member.indos);
  setValue("sid", member.sid);
  setValue("dob", normalizeDateForInput(member.dob));
  setValue("age", member.age);
  setValue("blood", member.blood);
  setValue("rank", member.rank);
  setValue("mobile", digitsOnly(member.mobile).slice(-10));
  setValue("altmobile", digitsOnly(member.altmobile).slice(-10));
  setValue("email", member.email);
  setValue("altemail", member.altemail);
  setValue("address1", member.address1 || member.fullAddress || "");
  setValue("address2", member.address2);
  setValue("address3", member.address3);
  setValue("city", member.city);
  setSelectValue("state", member.state);
  setValue("pincode", digitsOnly(member.pincode).slice(-6));

  const nominee = String(member.nominee || "Spouse").toLowerCase() === "mother" ? "Mother" : "Spouse";
  const nomineeRadio = document.querySelector(`input[name="nominee"][value="${nominee}"]`);
  if (nomineeRadio) nomineeRadio.checked = true;
  setNomineeUI();
  if (nominee === "Spouse") setValue("spousePhone", digitsOnly(member.spousePhone).slice(-10));

  if (value("dob")) calculateAge();

  closeMemberModal();
  switchTab("formTab");
  showStatus("Existing member details loaded. Check the details and submit for New Card.", true);

  setTimeout(() => {
    document.querySelector(".card").scrollIntoView({ behavior: "smooth", block: "start" });
  }, 100);
}

async function submitForm(event) {
  event.preventDefault();

  const form = document.getElementById("memberForm");
  if (!form.reportValidity()) return;

  const age = parseInt(document.getElementById("age").value, 10);
  if (isNaN(age) || age < 18 || age > 60) {
    showStatus("Not Eligible. Age should be between 18 and 60 years.", false);
    return;
  }

  setLoading(true);
  showStatus("", true, true);

  let waWindow = null;
  try {
    waWindow = window.open("about:blank", "_blank");
  } catch (_) {}

  try {
    const memberType = checkedValue("memberType") || "New Member";
    const nominee = checkedValue("nominee") || "Spouse";

    const data = {
      memberType,
      name: value("name"),
      cdc: value("cdc").toUpperCase(),
      indos: value("indos").toUpperCase(),
      sid: value("sid").toUpperCase(),
      dob: value("dob"),
      age: value("age"),
      blood: value("blood"),
      rank: value("rank"),
      nominee,
      spousePhone: nominee === "Spouse" ? value("spousePhone") : "",
      mobile: value("mobile"),
      altmobile: value("altmobile"),
      email: value("email"),
      altemail: value("altemail"),
      address1: value("address1"),
      address2: value("address2"),
      address3: value("address3"),
      city: value("city"),
      state: value("state"),
      pincode: value("pincode")
    };

    const photoFile = file("photo");
    const cdcFile = file("cdcfile");
    const passportFile = file("passport");
    const signFile = file("sign");

    if (photoFile) data.photo = await fileToBase64(photoFile);
    if (cdcFile) data.cdcfile = await fileToBase64(cdcFile);
    if (passportFile) data.passport = await fileToBase64(passportFile);
    if (signFile) data.sign = await fileToBase64(signFile);

    const response = await fetch("/api/member", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data)
    });

    const result = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(result.error || "Membership submission failed.");
    }

    const waUrl = makeWhatsAppUrl(result);
    if (waWindow && !waWindow.closed) {
      waWindow.location.href = waUrl;
    } else {
      window.location.href = waUrl;
    }

    form.reset();
    document.querySelector('input[name="memberType"][value="New Member"]').checked = true;
    document.querySelector('input[name="nominee"][value="Spouse"]').checked = true;
    document.getElementById("age").value = "";
    document.getElementById("eligibility").textContent = "";
    document.getElementById("existingIndos").value = "";
    fetchedMember = null;
    setMemberTypeUI();
    setNomineeUI();
    showStatus("Membership Submitted Successfully.", true);
  } catch (err) {
    if (waWindow && !waWindow.closed) waWindow.close();
    showStatus(err.message || "Membership submission failed.", false);
  } finally {
    setLoading(false);
  }
}

function makeWhatsAppUrl(d) {
  const msg = `*NUSI MEMBERSHIP 2026*

*Membership Type:* ${d.memberType}
*Name:* ${d.name}
*CDC No:* ${d.cdc}
*INDoS No:* ${d.indos}
*SID No:* ${d.sid || "-"}
*DOB:* ${d.dob}
*Age:* ${d.age}
*Blood Group:* ${d.blood}
*Rank:* ${d.rank}

*Nominee:* ${d.nominee}
*Spouse Phone:* ${d.spousePhone || "-"}

*Mobile:* ${d.mobile}
*Alternate Mobile:* ${d.altmobile}

*Email:* ${d.email}
*Alternate Email:* ${d.altemail}

*Address Line 1:* ${d.address1}
*Address Line 2:* ${d.address2}
*Address Line 3:* ${d.address3}

*City:* ${d.city}
*State:* ${d.state}
*Pincode:* ${d.pincode}

*Photo URL*
${d.photo}

*CDC PDF URL*
${d.cdcfile}

*Passport PDF URL*
${d.passport}

*Signature URL*
${d.sign}

*Folder URL*
${d.folder}`;

  return `https://wa.me/${WHATSAPP_NUMBER}?text=${encodeURIComponent(msg)}`;
}

function calculateAge() {
  const dob = value("dob");
  if (!dob) return;

  const [year, month, day] = dob.split("-").map(Number);
  const today = new Date();
  let age = today.getFullYear() - year;
  const m = today.getMonth() + 1 - month;

  if (m < 0 || (m === 0 && today.getDate() < day)) age--;

  document.getElementById("age").value = age;
  const status = document.getElementById("eligibility");

  if (age >= 18 && age <= 60) {
    status.textContent = "✅ Eligible";
    status.style.color = "#198754";
  } else {
    status.textContent = "❌ Not Eligible";
    status.style.color = "#dc3545";
  }
}

function fileToBase64(f) {
  return new Promise((resolve, reject) => {
    if (!f) return reject(new Error("Required file is missing."));

    const reader = new FileReader();
    reader.onload = (e) => {
      resolve({
        name: f.name,
        mime: f.type,
        data: e.target.result.split(",")[1]
      });
    };
    reader.onerror = () => reject(new Error(`Unable to read ${f.name}.`));
    reader.readAsDataURL(f);
  });
}

function normalizeDateForInput(raw) {
  const text = String(raw || "").trim();
  if (!text) return "";
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text;

  const dmy = text.match(/^(\d{1,2})[\/-](\d{1,2})[\/-](\d{4})$/);
  if (dmy) {
    return `${dmy[3]}-${String(dmy[2]).padStart(2, "0")}-${String(dmy[1]).padStart(2, "0")}`;
  }

  const parsed = new Date(text);
  if (Number.isNaN(parsed.getTime())) return "";
  const y = parsed.getFullYear();
  const m = String(parsed.getMonth() + 1).padStart(2, "0");
  const d = String(parsed.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function digitsOnly(value) {
  return String(value || "").replace(/\D/g, "");
}

function escapeHtml(value) {
  return String(value || "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function checkedValue(name) {
  return document.querySelector(`input[name="${name}"]:checked`)?.value || "";
}

function value(id) {
  return document.getElementById(id)?.value?.trim() || "";
}

function file(id) {
  return document.getElementById(id)?.files?.[0] || null;
}

function setValue(id, val) {
  const el = document.getElementById(id);
  if (el) el.value = val == null ? "" : String(val);
}

function setSelectValue(id, val) {
  const el = document.getElementById(id);
  if (!el) return;
  const wanted = String(val || "").trim().toUpperCase();
  const option = Array.from(el.options).find((o) => o.value.trim().toUpperCase() === wanted);
  el.value = option ? option.value : "";
}

function setLoading(active) {
  document.getElementById("loading").hidden = !active;
  document.getElementById("submitBtn").disabled = active;
}

function showStatus(message, ok, hide = false) {
  const el = document.getElementById("status");
  if (hide || !message) {
    el.hidden = true;
    el.textContent = "";
    el.className = "status";
    return;
  }
  el.hidden = false;
  el.textContent = message;
  el.className = `status ${ok ? "ok" : "error"}`;
  el.scrollIntoView({ behavior: "smooth", block: "nearest" });
}
