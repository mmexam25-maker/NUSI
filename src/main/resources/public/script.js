let sliderImages = [];
let sliderIndex = 0;
let sliderTimer = null;

const $ = id => document.getElementById(id);

const val = id => {
    const el = $(id);
    return el ? el.value.trim() : "";
};


/*************************************************
 * PAGE LOAD
 *************************************************/

window.addEventListener("load", function () {

    loadSlider();

    const name = $("name");

    if (name) {
        name.addEventListener("input", function () {
            this.value = toTitleCase(this.value);
        });
    }

    const city = $("city");

    if (city) {
        city.addEventListener("input", function () {
            this.value = toTitleCase(this.value);
        });
    }

    const form = $("memberForm");

    if (form) {
        form.addEventListener("submit", function (e) {

            e.preventDefault();

            submitForm();

        });
    }

});


/*************************************************
 * TITLE CASE
 *************************************************/

function toTitleCase(str) {

    return String(str || "")
        .toLowerCase()
        .replace(/\b\w/g, function (c) {
            return c.toUpperCase();
        });

}


/*************************************************
 * LOAD SLIDER
 *************************************************/

async function loadSlider() {

    const box = $("sliderImages");

    if (!box) return;


    try {

        const response = await fetch(
            "/api/slider",
            {
                cache: "no-store"
            }
        );


        const result =
            await response.json();


        if (!response.ok) {

            throw new Error(
                result.error ||
                "Unable to load updates."
            );
        }


        sliderImages =
            Array.isArray(result)
                ? result
                : [];


        if (sliderImages.length === 0) {

            box.innerHTML =
                '<div class="slider-empty">No updates available.</div>';

            return;
        }


        box.innerHTML = `

            <div class="slider-stage">

                <img
                    id="sliderMainPhoto"
                    alt="NUSI Update"
                >

                <button
                    type="button"
                    class="slider-arrow prev"
                    aria-label="Previous"
                >
                    ❮
                </button>

                <button
                    type="button"
                    class="slider-arrow next"
                    aria-label="Next"
                >
                    ❯
                </button>

                <div
                    id="sliderDots"
                    class="slider-dots"
                ></div>

            </div>

        `;


        box
            .querySelector(".prev")
            .addEventListener(
                "click",
                function () {
                    moveSlider(-1);
                }
            );


        box
            .querySelector(".next")
            .addEventListener(
                "click",
                function () {
                    moveSlider(1);
                }
            );


        const dots =
            $("sliderDots");


        sliderImages.forEach(
            function (img, index) {

                const dot =
                    document.createElement(
                        "span"
                    );


                dot.className =
                    "slider-dot";


                dot.addEventListener(
                    "click",
                    function () {

                        sliderIndex =
                            index;

                        showSlide();

                        restartSlider();

                    }
                );


                dots.appendChild(dot);

            }
        );


        sliderIndex = 0;

        showSlide();

        startSlider();


    } catch (err) {

        console.error(err);

        box.innerHTML =
            '<div class="slider-empty">Unable to load updates.</div>';

    }

}


/*************************************************
 * SHOW SLIDE
 *************************************************/

function showSlide() {

    if (!sliderImages.length) {
        return;
    }


    const img =
        $("sliderMainPhoto");


    if (!img) {
        return;
    }


    img.src =
        sliderImages[
            sliderIndex
        ].url;


    img.alt =
        sliderImages[
            sliderIndex
        ].name ||
        "NUSI Update";


    document
        .querySelectorAll(
            ".slider-dot"
        )
        .forEach(
            function (dot, index) {

                dot.classList.toggle(
                    "active",
                    index === sliderIndex
                );

            }
        );

}


/*************************************************
 * MOVE SLIDER
 *************************************************/

function moveSlider(step) {

    if (!sliderImages.length) {
        return;
    }


    sliderIndex =
        (
            sliderIndex +
            step +
            sliderImages.length
        ) %
        sliderImages.length;


    showSlide();

    restartSlider();

}


/*************************************************
 * AUTO SLIDER
 *************************************************/

function startSlider() {

    clearInterval(
        sliderTimer
    );


    if (
        sliderImages.length <= 1
    ) {
        return;
    }


    sliderTimer =
        setInterval(
            function () {

                sliderIndex =
                    (
                        sliderIndex + 1
                    ) %
                    sliderImages.length;


                showSlide();

            },
            3000
        );

}


function restartSlider() {

    clearInterval(
        sliderTimer
    );

    startSlider();

}


/*************************************************
 * CALCULATE AGE
 *************************************************/

function calculateAge() {

    const dob =
        val("dob");


    if (!dob) {
        return;
    }


    const birth =
        new Date(
            dob + "T00:00:00"
        );


    if (
        Number.isNaN(
            birth.getTime()
        )
    ) {
        return;
    }


    const today =
        new Date();


    let age =
        today.getFullYear() -
        birth.getFullYear();


    const monthDifference =
        today.getMonth() -
        birth.getMonth();


    if (
        monthDifference < 0 ||
        (
            monthDifference === 0 &&
            today.getDate() <
            birth.getDate()
        )
    ) {

        age--;

    }


    $("age").value =
        age;


    const status =
        $("eligibility");


    if (
        age >= 18 &&
        age <= 60
    ) {

        status.textContent =
            "✅ Eligible";

        status.style.color =
            "#198754";

    } else {

        status.textContent =
            "❌ Not Eligible";

        status.style.color =
            "#dc3545";

    }

}


/*************************************************
 * SUBMIT MEMBERSHIP
 *************************************************/

async function submitForm() {

    const age =
        parseInt(
            val("age"),
            10
        );


    if (
        !Number.isFinite(age) ||
        age < 18 ||
        age > 60
    ) {

        alert(
            "Not Eligible. Age should be between 18 and 60 years."
        );

        return;
    }


    /*********************************************
     * INDOS PASSWORD
     *********************************************/

    if (
        !val("indosPassword")
    ) {

        alert(
            "Please enter INDoS Password."
        );

        return;
    }


    /*********************************************
     * FILES
     *********************************************/

    const cdcInput =
        $("cdcfile");

    const passportInput =
        $("passport");


    const cdcFile =
        cdcInput &&
        cdcInput.files
            ? cdcInput.files[0]
            : null;


    const passportFile =
        passportInput &&
        passportInput.files
            ? passportInput.files[0]
            : null;


    if (
        !cdcFile ||
        !passportFile
    ) {

        alert(
            "Please upload CDC and Passport PDF."
        );

        return;
    }


    if (
        cdcFile.type !==
            "application/pdf" ||
        passportFile.type !==
            "application/pdf"
    ) {

        alert(
            "CDC and Passport must be PDF files."
        );

        return;
    }


    setLoading(true);


    try {

        /*****************************************
         * FORM DATA
         *****************************************/

        const data = {

            name:
                val("name"),

            cdc:
                val("cdc")
                    .toUpperCase(),

            indos:
                val("indos")
                    .toUpperCase(),

            /*
             * Used by Java only for DG login.
             * Not stored in sheet.
             */
            indosPassword:
                val(
                    "indosPassword"
                ),

            dob:
                val("dob"),

            age:
                val("age"),

            blood:
                val("blood"),

            rank:
                val("rank"),

            mobile:
                val("mobile"),

            altmobile:
                val("altmobile"),

            email:
                val("email"),

            altemail:
                val("altemail"),

            address1:
                val("address1"),

            address2:
                val("address2"),

            address3:
                val("address3"),

            city:
                val("city"),

            state:
                val("state"),

            pincode:
                val("pincode"),

            cdcfile:
                await fileToBase64(
                    cdcFile
                ),

            passport:
                await fileToBase64(
                    passportFile
                )

        };


        /*****************************************
         * SEND TO JAVA SERVER
         *****************************************/

        const response =
            await fetch(
                "/api/submit",
                {

                    method:
                        "POST",

                    headers: {

                        "Content-Type":
                            "application/json"

                    },

                    body:
                        JSON.stringify(
                            data
                        )

                }
            );


        let result;


        try {

            result =
                await response.json();

        } catch (err) {

            throw new Error(
                "Server returned an invalid response."
            );

        }


        if (
            !response.ok ||
            !result.success
        ) {

            throw new Error(
                result.message ||
                result.error ||
                "Membership submission failed."
            );

        }


        /*****************************************
         * SUCCESS
         *****************************************/

        alert(
            "Membership Submitted Successfully."
        );


        whatsapp(
            result
        );


        $("memberForm")
            .reset();


        $("eligibility")
            .textContent = "";


    } catch (err) {

        console.error(err);


        alert(
            err.message ||
            String(err)
        );


    } finally {

        setLoading(false);

    }

}


/*************************************************
 * LOADING
 *************************************************/

function setLoading(on) {

    const loading =
        $("loading");

    const button =
        $("submitBtn");


    if (loading) {

        loading.style.display =
            on
                ? "block"
                : "none";

    }


    if (button) {

        button.disabled =
            on;

    }

}


/*************************************************
 * FILE -> BASE64
 *************************************************/

function fileToBase64(file) {

    return new Promise(
        function (
            resolve,
            reject
        ) {

            const reader =
                new FileReader();


            reader.onload =
                function (event) {

                    const raw =
                        String(
                            event.target.result ||
                            ""
                        );


                    resolve({

                        name:
                            file.name,

                        mime:
                            file.type,

                        data:
                            raw.includes(",")
                                ? raw.split(",")[1]
                                : raw

                    });

                };


            reader.onerror =
                function () {

                    reject(
                        new Error(
                            "File could not be read."
                        )
                    );

                };


            reader.readAsDataURL(
                file
            );

        }
    );

}


/*************************************************
 * WHATSAPP
 *************************************************/

function whatsapp(d) {

    const number =
        "917538848180";


    const msg =
`*NUSI NEW MEMBERSHIP 2026*

*Name:* ${d.name || ""}
*CDC No:* ${d.cdc || ""}
*INDoS No:* ${d.indos || ""}
*DOB:* ${d.dob || ""}
*Age:* ${d.age || ""}
*Blood Group:* ${d.blood || ""}
*Rank:* ${d.rank || ""}

*Mobile:* ${d.mobile || ""}
*Alternate Mobile:* ${d.altmobile || ""}

*Email:* ${d.email || ""}
*Alternate Email:* ${d.altemail || ""}

*Address:*
${d.address || ""}

*DG Profile Photo*
${d.photo || ""}

*CDC PDF*
${d.cdcfile || ""}

*Passport PDF*
${d.passport || ""}

*Folder*
${d.folder || ""}`;


    window.open(
        "https://wa.me/" +
        number +
        "?text=" +
        encodeURIComponent(
            msg
        ),
        "_blank"
    );

}
