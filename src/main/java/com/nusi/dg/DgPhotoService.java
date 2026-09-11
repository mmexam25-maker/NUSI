package com.nusi.dg;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DgPhotoService {

    private static final String DG_BASE = "http://220.156.189.33/esamudraUI";
    private static final String DG_LOGIN_URL = DG_BASE + "/logOut.do?method=loadIndexPage";

    private DgPhotoService() {}

    public record PhotoResult(String base64, int width, int height) {}

    public static PhotoResult fetch(String indos, String password) throws Exception {
        WebDriver driver = null;
        try {
            driver = createDriver();

            if (!attemptLogin(driver, indos, password)) {
                throw new IllegalArgumentException("DG login failed. Check INDoS number / password.");
            }

            openUpdateProfile(driver);

            byte[] pdf = downloadProfilePdf(driver, indos);
            BufferedImage photo = extractPhoto(pdf);

            if (photo == null) {
                throw new IllegalStateException("Candidate photo could not be extracted from DG Profile PDF.");
            }

            BufferedImage prepared = preparePhoto(photo);
            byte[] jpg = writeJpeg(prepared, 0.88f);
            String base64 = Base64.getEncoder().encodeToString(jpg);

            return new PhotoResult(base64, prepared.getWidth(), prepared.getHeight());

        } finally {
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignored) {}
            }
        }
    }

    private static WebDriver createDriver() {
        String chrome = env("CHROME_BIN", "/usr/bin/chromium");
        String driverPath = env("CHROMEDRIVER", "/usr/bin/chromedriver");

        System.setProperty("webdriver.chrome.driver", driverPath);

        ChromeOptions options = new ChromeOptions();
        options.setBinary(chrome);
        options.addArguments(
                "--headless=new",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=1440,1000",
                "--disable-extensions",
                "--disable-background-networking",
                "--disable-sync",
                "--metrics-recording-only",
                "--no-first-run",
                "--disable-default-apps",
                "--disable-features=Translate,MediaRouter"
        );
        options.setAcceptInsecureCerts(true);

        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
        return driver;
    }

    private static boolean attemptLogin(WebDriver driver, String indos, String password) throws Exception {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        driver.get(DG_LOGIN_URL);

        WebElement passwordBox;
        try {
            passwordBox = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("input[type='password']")));
        } catch (TimeoutException firstPageNotLogin) {
            WebElement loginLink = firstVisibleEnabled(driver, List.of(
                    By.linkText("Login"),
                    By.xpath("//a[normalize-space()='Login']"),
                    By.xpath("//button[normalize-space()='Login']")
            ));
            if (loginLink != null) click(driver, loginLink);
            passwordBox = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("input[type='password']")));
        }

        WebElement userIdBox = firstVisibleEnabled(driver, List.of(
                By.cssSelector("input[name='userId']"),
                By.cssSelector("input[id='userId']"),
                By.cssSelector("input[name*='user' i]"),
                By.cssSelector("input[id*='user' i]"),
                By.xpath("//input[(@type='text' or not(@type)) and not(@disabled)]")
        ));

        if (userIdBox == null) throw new IllegalStateException("DG User Id field not found.");

        userIdBox.clear();
        userIdBox.sendKeys(indos);
        passwordBox.clear();
        passwordBox.sendKeys(password);

        WebElement loginButton = firstVisibleEnabled(driver, List.of(
                By.xpath("//input[@type='submit' and translate(@value,'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')='LOGIN']"),
                By.xpath("//input[@type='button' and translate(@value,'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')='LOGIN']"),
                By.xpath("//button[normalize-space()='Login']"),
                By.xpath("//input[@value='Login']")
        ));

        if (loginButton == null) throw new IllegalStateException("DG Login button not found.");
        click(driver, loginButton);

        long end = System.currentTimeMillis() + 25_000L;
        while (System.currentTimeMillis() < end) {
            String body = safeBodyText(driver);
            String lower = body.toLowerCase(Locale.ROOT);
            String upper = body.toUpperCase(Locale.ROOT);

            if (lower.contains("username and password") && lower.contains("does not match")) {
                return false;
            }

            boolean logoutPresent = upper.contains("LOG OUT")
                    || upper.contains("LOGOUT")
                    || !driver.findElements(By.xpath("//a[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'LOG OUT')]")).isEmpty()
                    || !driver.findElements(By.xpath("//a[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'LOGOUT')]")).isEmpty();

            boolean welcomePresent = lower.contains("welcome ");
            boolean resetPasswordPresent = upper.contains("RESET PASSWORD");
            boolean loginFormStillPresent = !driver.findElements(By.cssSelector("input[type='password']")).isEmpty();

            if (welcomePresent && (logoutPresent || resetPasswordPresent) && !loginFormStillPresent) {
                System.out.println("DG AUTHENTICATED HOME CONFIRMED | INDoS " + indos);
                return true;
            }

            Thread.sleep(400L);
        }

        return false;
    }

    private static void openUpdateProfile(WebDriver driver) throws Exception {
        String homeHandle = driver.getWindowHandle();

        for (int attempt = 1; attempt <= 5; attempt++) {
            WebElement updateProfile = firstVisibleEnabled(driver, List.of(
                    By.xpath("//a[normalize-space()='Update Seafarer Profile']"),
                    By.xpath("//a[contains(normalize-space(.),'Update Seafarer Profile')]")
            ));

            if (updateProfile == null) {
                driver.navigate().refresh();
                Thread.sleep(1400L);
                continue;
            }

            Set<String> before = new HashSet<>(driver.getWindowHandles());
            click(driver, updateProfile);

            try {
                Alert alert = new WebDriverWait(driver, Duration.ofSeconds(2))
                        .until(ExpectedConditions.alertIsPresent());
                String text = alert == null ? "" : alert.getText();
                if (alert != null) alert.accept();
                if (text.toLowerCase(Locale.ROOT).contains("please log in")) {
                    throw new IllegalStateException("DG session expired while opening Update Seafarer Profile.");
                }
            } catch (TimeoutException ignored) {
                // Normal authenticated path.
            }

            long end = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < end) {
                Set<String> now = driver.getWindowHandles();
                if (now.size() > before.size()) {
                    for (String handle : now) {
                        if (!before.contains(handle)) {
                            driver.switchTo().window(handle);
                            break;
                        }
                    }
                }

                String body = safeBodyText(driver).toLowerCase(Locale.ROOT);
                String source = safePageSource(driver).toLowerCase(Locale.ROOT);

                if (body.contains("click to view and print your profile")
                        || source.contains("viewprofilereport")
                        || !driver.findElements(By.xpath("//a[contains(normalize-space(.),'Click to View and Print Your Profile')]")).isEmpty()) {
                    System.out.println("DG UPDATE SEAFARER PROFILE OPENED");
                    return;
                }

                if (body.contains("500 internal server error") || body.contains("dofilter methodnull")) {
                    break;
                }

                Thread.sleep(400L);
            }

            closeExtraWindowsAndReturn(driver, homeHandle);
            driver.navigate().refresh();
            Thread.sleep(1500L + attempt * 300L);
        }

        throw new IllegalStateException("DG Update Seafarer Profile could not open after retries.");
    }

    private static void closeExtraWindowsAndReturn(WebDriver driver, String homeHandle) {
        try {
            for (String handle : new HashSet<>(driver.getWindowHandles())) {
                if (!handle.equals(homeHandle)) {
                    try {
                        driver.switchTo().window(handle);
                        driver.close();
                    } catch (Exception ignored) {}
                }
            }
            if (driver.getWindowHandles().contains(homeHandle)) driver.switchTo().window(homeHandle);
        } catch (Exception ignored) {}
    }

    private static byte[] downloadProfilePdf(WebDriver driver, String indos) throws Exception {
        String reportUrl = DG_BASE + "/reportServlet?processId=SfrrProfile&Indosno="
                + URLEncoder.encode(indos, StandardCharsets.UTF_8);

        String cookieHeader = driver.manage().getCookies().stream()
                .map(c -> c.getName() + "=" + c.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");

        if (cookieHeader.isBlank()) {
            throw new IllegalStateException("DG authenticated cookies were not available.");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(reportUrl))
                        .timeout(Duration.ofSeconds(45))
                        .header("Cookie", cookieHeader)
                        .header("Referer", driver.getCurrentUrl())
                        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/152 Safari/537.36")
                        .header("Accept", "application/pdf,*/*;q=0.8")
                        .GET()
                        .build();

                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                byte[] body = response.body();

                boolean pdf = response.statusCode() >= 200
                        && response.statusCode() < 300
                        && body != null
                        && body.length > 1000
                        && body.length >= 4
                        && body[0] == '%'
                        && body[1] == 'P'
                        && body[2] == 'D'
                        && body[3] == 'F';

                if (pdf) {
                    System.out.println("DG PROFILE PDF DOWNLOADED | bytes=" + body.length);
                    return body;
                }

                last = new IllegalStateException("DG profile PDF response was not a PDF. HTTP " + response.statusCode());
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(900L * attempt);
        }

        throw new IllegalStateException("DG Profile PDF download failed: " + (last == null ? "unknown error" : last.getMessage()));
    }

    private static BufferedImage extractPhoto(byte[] pdfBytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PhotoCandidate best = null;
            int maxPages = Math.min(document.getNumberOfPages(), 4);

            for (int pageIndex = 0; pageIndex < maxPages; pageIndex++) {
                PDPage page = document.getPage(pageIndex);
                List<PhotoCandidate> found = new ArrayList<>();
                collectImages(page.getResources(), pageIndex, 0, found);
                for (PhotoCandidate candidate : found) {
                    if (best == null || candidate.score > best.score) best = candidate;
                }
            }

            if (best != null) {
                System.out.println("DG PHOTO SOURCE | embedded PDF image "
                        + best.image.getWidth() + "x" + best.image.getHeight());
                return best.image;
            }

            BufferedImage fallback = extractCandidatePhotoFromRenderedPage(document);
            if (fallback != null) {
                System.out.println("DG PHOTO SOURCE | rendered page-1 crop "
                        + fallback.getWidth() + "x" + fallback.getHeight());
            }
            return fallback;
        }
    }

    private static void collectImages(PDResources resources, int pageIndex, int depth, List<PhotoCandidate> out)
            throws IOException {
        if (resources == null || depth > 3) return;

        for (COSName name : resources.getXObjectNames()) {
            PDXObject object;
            try {
                object = resources.getXObject(name);
            } catch (Exception ignored) {
                continue;
            }

            if (object instanceof PDImageXObject imageObject) {
                BufferedImage image;
                try {
                    image = imageObject.getImage();
                } catch (Exception ignored) {
                    continue;
                }
                if (image == null) continue;

                int width = image.getWidth();
                int height = image.getHeight();
                if (width < 45 || height < 55) continue;

                double ratio = width / (double) height;
                if (ratio < 0.48 || ratio > 1.35) continue;

                double score = width * (double) height;
                if (pageIndex == 0) score *= 5.0;
                if (ratio >= 0.65 && ratio <= 1.20) score *= 2.5;

                out.add(new PhotoCandidate(image, score));

            } else if (object instanceof PDFormXObject form) {
                collectImages(form.getResources(), pageIndex, depth + 1, out);
            }
        }
    }

    private static BufferedImage extractCandidatePhotoFromRenderedPage(PDDocument document) throws IOException {
        if (document == null || document.getNumberOfPages() == 0) return null;

        PDFRenderer renderer = new PDFRenderer(document);
        BufferedImage page = renderer.renderImageWithDPI(0, 180);
        if (page == null || page.getWidth() < 300 || page.getHeight() < 400) return null;

        int x1 = clamp((int) Math.round(page.getWidth() * 0.805), 0, page.getWidth() - 2);
        int y1 = clamp((int) Math.round(page.getHeight() * 0.145), 0, page.getHeight() - 2);
        int x2 = clamp((int) Math.round(page.getWidth() * 0.945), x1 + 1, page.getWidth());
        int y2 = clamp((int) Math.round(page.getHeight() * 0.248), y1 + 1, page.getHeight());

        BufferedImage crop = copySubImage(page, x1, y1, x2 - x1, y2 - y1);
        crop = trimNearWhiteBorder(crop);

        if (crop == null || crop.getWidth() < 55 || crop.getHeight() < 55) return null;
        return crop;
    }

    private static BufferedImage preparePhoto(BufferedImage source) {
        final int canvasSize = 600;
        final int margin = 18;
        final int available = canvasSize - margin * 2;

        double scale = Math.min(
                available / (double) Math.max(1, source.getWidth()),
                available / (double) Math.max(1, source.getHeight())
        );

        int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        int x = (canvasSize - drawWidth) / 2;
        int y = (canvasSize - drawHeight) / 2;

        BufferedImage canvas = new BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, canvasSize, canvasSize);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, x, y, drawWidth, drawHeight, null);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private static byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IOException("JPEG writer not available.");

        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            out.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage copySubImage(BufferedImage source, int x, int y, int width, int height) {
        BufferedImage out = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(source, 0, 0, out.getWidth(), out.getHeight(), x, y, x + width, y + height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage trimNearWhiteBorder(BufferedImage image) {
        if (image == null) return null;

        int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >>> 16) & 0xff;
                int g = (rgb >>> 8) & 0xff;
                int b = rgb & 0xff;
                if (r < 242 || g < 242 || b < 242) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX || maxY < minY) return image;

        int padX = Math.max(2, image.getWidth() / 40);
        int padY = Math.max(2, image.getHeight() / 40);
        minX = Math.max(0, minX - padX);
        minY = Math.max(0, minY - padY);
        maxX = Math.min(image.getWidth() - 1, maxX + padX);
        maxY = Math.min(image.getHeight() - 1, maxY + padY);

        return copySubImage(image, minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static WebElement firstVisibleEnabled(WebDriver driver, List<By> selectors) {
        for (By by : selectors) {
            try {
                for (WebElement el : driver.findElements(by)) {
                    if (el.isDisplayed() && el.isEnabled()) return el;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void click(WebDriver driver, WebElement element) {
        try {
            element.click();
        } catch (Exception normalClickFailed) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
        }
    }

    private static String safeBodyText(WebDriver driver) {
        try {
            return driver.findElement(By.tagName("body")).getText();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String safePageSource(WebDriver driver) {
        try {
            return driver.getPageSource();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record PhotoCandidate(BufferedImage image, double score) {}
}
