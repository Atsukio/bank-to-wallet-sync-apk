// Renders the Google Play icon (512x512) and feature graphic (1024x500) for the Android app.
// Usage: node store/make-graphics.mjs <extension dir> <output dir>. Uses the extension's puppeteer, sharp, icon and font.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const [ext, out] = process.argv.slice(2);
const require = createRequire(path.join(ext, 'package.json'));
const puppeteer = require('puppeteer-core');
const sharp = require('sharp');

const svg = fs.readFileSync(path.join(ext, 'icons/icon.svg'), 'utf8').replace(/<title>.*?<\/title>/, '');
const font = fs.readFileSync(path.join(ext, 'fonts/roboto-latin.woff2')).toString('base64');
const icon = (size) => svg.replace(/width="\d+" height="\d+"/, `width="${size}" height="${size}"`);
const base = `@font-face{font-family:Roboto;font-weight:100 900;src:url(data:font/woff2;base64,${font}) format('woff2')}
  *{margin:0;box-sizing:border-box} body{font-family:Roboto;overflow:hidden}`;

const pages = {
  // Same look as the adaptive launcher icon: teal background, icon in the middle 60/108.
  'play-icon-512.png': [512, 512, `<style>${base} body{width:512px;height:512px;background:#0E6F66;display:grid;place-items:center}</style>${icon(284)}`],
  'feature-graphic-1024x500.png': [1024, 500, `<style>${base}
    body{width:1024px;height:500px;background:linear-gradient(135deg,#0E6F66,#0B3D38);color:#fff;display:flex;align-items:center;gap:56px;padding:0 96px}
    .tile{width:208px;height:208px;border-radius:48px;background:#F4FBF8;display:grid;place-items:center;flex:none;box-shadow:0 16px 40px rgba(0,0,0,.25)}
    h1{font-size:64px;font-weight:700;letter-spacing:-1px} p{font-size:30px;line-height:1.35;margin-top:16px;color:#CCE8E2;max-width:560px}
    </style><div class="tile">${icon(144)}</div><div><h1>Bank to Wallet</h1><p>Bank alerts become BudgetBakers Wallet records, automatically.</p></div>`],
};

const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH, headless: 'new', args: ['--no-sandbox'] });
for (const [file, [w, h, html]] of Object.entries(pages)) {
  const page = await browser.newPage();
  await page.setViewport({ width: w, height: h, deviceScaleFactor: 1 });
  await page.setContent(`<!doctype html><meta charset="utf-8">${html}`);
  await page.evaluate(() => document.fonts.ready);
  const shot = await page.screenshot({ type: 'png' });
  // Opaque 24-bit PNG: Play rejects an alpha channel in the feature graphic.
  await sharp(shot).flatten({ background: '#0E6F66' }).removeAlpha().png().toFile(path.join(out, file));
  console.log('wrote', file);
  await page.close();
}
await browser.close();
