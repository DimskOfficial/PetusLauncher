#!/usr/bin/env node
// Publish launcher + game artifacts to S3 — no AWS CLI required (pure SigV4).
//
// Usage:
//   AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... node publish.mjs <command> [args]
//
// Commands:
//   installer <path-to-Setup.exe>        -> launcher/PetusLauncher-Setup.exe
//   game <path-to-zip> <version>         -> game/petusgdps-<version>.zip
//                                           + rewrites game/version.json
//   put <localFile> <s3key> [contentType]
//
// Config via env (with sane defaults):
//   S3_BUCKET   (default petusru)
//   S3_REGION   (default eu-north-1)
//   CDN_BASE    (default https://cdn.petus.goonhost.rocks)

import fs from 'node:fs';
import path from 'node:path';
import https from 'node:https';
import crypto from 'node:crypto';

const AK = process.env.AWS_ACCESS_KEY_ID;
const SK = process.env.AWS_SECRET_ACCESS_KEY;
const BUCKET = process.env.S3_BUCKET || 'petusru';
const REGION = process.env.S3_REGION || 'eu-north-1';
const CDN = (process.env.CDN_BASE || 'https://cdn.petus.goonhost.rocks').replace(/\/$/, '');
const HOST = `${BUCKET}.s3.${REGION}.amazonaws.com`;

if (!AK || !SK) {
  console.error('Set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY.');
  process.exit(1);
}

const sha256hex = (b) => crypto.createHash('sha256').update(b).digest('hex');
const hmac = (k, s) => crypto.createHmac('sha256', k).update(s).digest();

function signedRequest({ method, key, body, contentType }) {
  return new Promise((resolve, reject) => {
    const now = new Date();
    const amzdate = now.toISOString().replace(/[:-]|\.\d{3}/g, '');
    const datestamp = amzdate.slice(0, 8);
    const payloadHash = sha256hex(body ?? Buffer.alloc(0));
    const canonicalUri = '/' + key.split('/').map(encodeURIComponent).join('/');

    const headers = {
      host: HOST,
      'x-amz-content-sha256': payloadHash,
      'x-amz-date': amzdate,
    };
    if (contentType) headers['content-type'] = contentType;
    // S3 rejects chunked uploads (Transfer-Encoding) — always send Content-Length.
    const bodyBuf = body ?? Buffer.alloc(0);
    if (method === 'PUT') headers['content-length'] = String(bodyBuf.length);

    const signedHeaders = Object.keys(headers).sort().join(';');
    const canonicalHeaders = Object.keys(headers)
      .sort()
      .map((h) => `${h}:${headers[h]}\n`)
      .join('');
    const canonicalRequest = [method, canonicalUri, '', canonicalHeaders, signedHeaders, payloadHash].join('\n');
    const scope = `${datestamp}/${REGION}/s3/aws4_request`;
    const sts = ['AWS4-HMAC-SHA256', amzdate, scope, sha256hex(canonicalRequest)].join('\n');
    let k = hmac('AWS4' + SK, datestamp);
    k = hmac(k, REGION);
    k = hmac(k, 's3');
    k = hmac(k, 'aws4_request');
    const sig = crypto.createHmac('sha256', k).update(sts).digest('hex');
    headers.Authorization = `AWS4-HMAC-SHA256 Credential=${AK}/${scope}, SignedHeaders=${signedHeaders}, Signature=${sig}`;

    const req = https.request({ host: HOST, path: canonicalUri, method, headers }, (res) => {
      let b = '';
      res.on('data', (d) => (b += d));
      res.on('end', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(b);
        else reject(new Error(`S3 ${method} ${key} -> ${res.statusCode}: ${b.slice(0, 300)}`));
      });
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

const CT = {
  '.exe': 'application/octet-stream',
  '.zip': 'application/zip',
  '.json': 'application/json',
};

async function put(localFile, key, contentType) {
  const body = fs.readFileSync(localFile);
  const ct = contentType || CT[path.extname(localFile).toLowerCase()] || 'application/octet-stream';
  await signedRequest({ method: 'PUT', key, body, contentType: ct });
  const mb = (body.length / 1048576).toFixed(2);
  console.log(`✓ ${key}  (${mb} MB)  -> ${CDN}/${key}`);
}

const [cmd, a1, a2] = process.argv.slice(2);

try {
  if (cmd === 'installer') {
    if (!a1) throw new Error('usage: installer <Setup.exe>');
    await put(a1, 'launcher/PetusLauncher-Setup.exe');
  } else if (cmd === 'game') {
    if (!a1 || !a2) throw new Error('usage: game <zip> <version>');
    const key = `game/petusgdps-${a2}.zip`;
    await put(a1, key);
    const manifest = JSON.stringify({ version: a2, url: `${CDN}/${key}` }, null, 2);
    await signedRequest({ method: 'PUT', key: 'game/version.json', body: Buffer.from(manifest), contentType: 'application/json' });
    console.log(`✓ game/version.json -> version ${a2}`);
  } else if (cmd === 'put') {
    if (!a1 || !a2) throw new Error('usage: put <localFile> <s3key> [contentType]');
    await put(a1, a2, process.argv[5]);
  } else {
    console.log('commands: installer <exe> | game <zip> <version> | put <file> <key> [ct]');
    process.exit(1);
  }
} catch (e) {
  console.error('✗', e.message);
  process.exit(1);
}
