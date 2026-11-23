import assert from "node:assert/strict";
import { chromium } from "playwright";

const baseURL = process.env.BASE_URL || "http://127.0.0.1:5173";
const executablePath = process.env.CHROMIUM_PATH || undefined;
const launchOptions = {
  headless: true,
  executablePath,
  args: ["--no-sandbox"],
};
const expectedWorkflow = "/actions/workflows/questcraft-mobile.yml";

async function runCase(name, viewport, apiMode) {
  const browser = await chromium.launch(launchOptions);
  const page = await browser.newPage({ viewport });
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.route("https://api.github.com/**", async (route) => {
    if (apiMode === "unavailable")
      return route.fulfill({ status: 503, body: "{}" });
    if (route.request().url().includes("/runs?")) {
      return route.fulfill({
        json: { workflow_runs: [{ id: 111 }, { id: 222 }] },
      });
    }
    const id = route.request().url().includes("/111/") ? 111 : 222;
    return route.fulfill({
      json: {
        artifacts: [
          {
            id: id + 1000,
            name: "QuestCraft-Mobile-unico-experimental",
            expired: apiMode === "expired-first" && id === 111,
            expires_at: "2026-12-06T23:51:29Z",
          },
        ],
      },
    });
  });
  try {
    await page.goto(baseURL, { waitUntil: "networkidle" });
    assert.match(await page.title(), /QuestCraft Mobile/);
    assert.match(await page.locator("h1").innerText(), /QuestCraft/);
    assert.doesNotMatch(
      await page.locator("body").innerText(),
      /Gorilla Rush|CraftVR/i,
    );
    assert.equal(await page.locator("#instalacao li").count(), 4);
    assert.match(
      await page.locator("#instalacao").innerText(),
      /Nenhum aplicativo auxiliar é necessário/i,
    );
    assert.doesNotMatch(
      await page.locator("#instalacao").innerText(),
      /Instale o PhoneXR|Abra pelo PhoneXR/i,
    );
    assert.match(
      await page.locator(".image-caption").innerText(),
      /não é uma captura/i,
    );
    assert.equal(await page.locator("img").count(), 2);
    for (const image of await page.locator("img").all()) {
      assert.equal(await image.evaluate((img) => img.naturalWidth > 0), true);
    }
    assert.match(
      await page.locator(".capture-card figcaption").innerText(),
      /emulador.*não é gameplay/i,
    );
    const href = await page
      .locator(".hero-actions .button-primary")
      .getAttribute("href");
    if (apiMode === "unavailable")
      assert.ok(href.includes(expectedWorkflow), href);
    else
      assert.ok(
        href.endsWith(
          apiMode === "expired-first" ? "/artifacts/1222" : "/artifacts/1111",
        ),
        href,
      );
    const size = await page.evaluate(() => ({
      width: document.documentElement.scrollWidth,
      viewport: window.innerWidth,
    }));
    assert.equal(
      size.width,
      size.viewport,
      "página não deve ter rolagem horizontal",
    );
    assert.deepEqual(errors, []);
    console.log(`OK ${name}`);
  } finally {
    await browser.close();
  }
}

await runCase(
  "desktop: build mais recente",
  { width: 1440, height: 1000 },
  "available",
);
await runCase(
  "celular: build anterior quando a mais recente expirou",
  { width: 390, height: 844 },
  "expired-first",
);
await runCase(
  "celular pequeno: fallback sem API",
  { width: 320, height: 700 },
  "unavailable",
);
