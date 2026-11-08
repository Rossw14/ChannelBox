// Works out which TV-listing websites to use for the channels in our playlists.
//
// Usage: node tools/make-guide-channels.mjs <playlists.txt> <epg/sites folder> <output.channels.xml>
//
// It reads the playlist links in playlists.txt, collects each channel's tvg-id,
// then looks through the iptv-org/epg site list for a listings source for each
// one. The result is a channels file that iptv-org's "grab" command can use.

import fs from 'node:fs'
import path from 'node:path'

const [listFile, sitesDir, outFile] = process.argv.slice(2)
if (!listFile || !sitesDir || !outFile) {
  console.error('Usage: node make-guide-channels.mjs <playlists.txt> <epg/sites> <output.xml>')
  process.exit(1)
}

// Best UK sources first. Anything else found is used if nothing here has the channel.
const PREFERRED = [
  'sky.com',
  'freeview.co.uk',
  'mytelly.co.uk',
  'tv24.co.uk',
  'player.ee.co.uk',
  'virgintvgo.virginmedia.com',
  'plex.tv',
  'pluto.tv',
  'tvireland.ie',
  'entertainment.ie',
  'xumo.tv',
  'watch.whaletvplus.com'
]
const SKIP = new Set(['i.mjh.nz']) // no longer published

const playlistUrls = fs
  .readFileSync(listFile, 'utf8')
  .split(/\r?\n/)
  .map(line => line.trim())
  .filter(line => line && !line.startsWith('#'))

// base id (no "@feed") -> set of full ids seen in the playlists
const wanted = new Map()
for (const url of playlistUrls) {
  try {
    const res = await fetch(url)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const text = await res.text()
    let count = 0
    for (const m of text.matchAll(/tvg-id="([^"]+)"/g)) {
      const full = m[1].trim().toLowerCase()
      const base = full.split('@')[0]
      if (!base) continue
      if (!wanted.has(base)) wanted.set(base, new Set())
      wanted.get(base).add(full)
      count++
    }
    console.log(`${url}: ${count} channels`)
  } catch (err) {
    console.warn(`Couldn't read ${url}: ${err.message}`)
  }
}

const best = new Map() // base id -> { score, xml }
for (const site of fs.readdirSync(sitesDir)) {
  if (SKIP.has(site)) continue
  const dir = path.join(sitesDir, site)
  if (!fs.statSync(dir).isDirectory()) continue
  const rank = PREFERRED.includes(site) ? PREFERRED.indexOf(site) : 50

  for (const file of fs.readdirSync(dir)) {
    if (!file.endsWith('.channels.xml')) continue
    const xml = fs.readFileSync(path.join(dir, file), 'utf8')
    for (const m of xml.matchAll(/<channel\b([^>]*)>([\s\S]*?)<\/channel>/g)) {
      const id = (m[1].match(/xmltv_id="([^"]*)"/) || [])[1]
      if (!id) continue
      const full = id.trim().toLowerCase()
      const base = full.split('@')[0]
      const feeds = wanted.get(base)
      if (!feeds) continue

      // Prefer the exact feed the playlist uses, then a UK feed, then any.
      const feedScore = feeds.has(full) ? 0 : full.endsWith('@uk') || !full.includes('@') ? 1 : 2
      const score = feedScore * 100 + rank
      const current = best.get(base)
      if (!current || score < current.score) best.set(base, { score, xml: m[0].trim() })
    }
  }
}

const lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<channels>']
for (const { xml } of best.values()) lines.push('  ' + xml)
lines.push('</channels>', '')
fs.writeFileSync(outFile, lines.join('\n'))

const bySite = {}
for (const { xml } of best.values()) {
  const site = (xml.match(/site="([^"]*)"/) || [])[1] || '?'
  bySite[site] = (bySite[site] || 0) + 1
}
console.log(`Listings found for ${best.size} of ${wanted.size} channels`)
console.log(bySite)
