// Joins several XMLTV guide files into one, dropping repeats.
//
// Usage: node tools/merge-guides.mjs <folder with .xml files (searched inside sub-folders)> <output.xml>

import fs from 'node:fs'
import path from 'node:path'

const [inputDir, outFile] = process.argv.slice(2)
if (!inputDir || !outFile) {
  console.error('Usage: node merge-guides.mjs <folder> <output.xml>')
  process.exit(1)
}

function findXml(dir) {
  if (!fs.existsSync(dir)) return []
  const found = []
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name)
    if (fs.statSync(full).isDirectory()) found.push(...findXml(full))
    else if (name.endsWith('.xml')) found.push(full)
  }
  return found
}

const attr = (tag, name) => (tag.match(new RegExp(`\\b${name}="([^"]*)"`)) || [])[1] || ''

const channels = new Map() // id -> xml
const programmes = new Map() // channel|start -> xml

const files = findXml(inputDir)
for (const file of files) {
  const xml = fs.readFileSync(file, 'utf8')
  let c = 0
  let p = 0
  for (const m of xml.matchAll(/<channel\b[^>]*>[\s\S]*?<\/channel>/g)) {
    const id = attr(m[0], 'id')
    if (id && !channels.has(id)) {
      channels.set(id, m[0])
      c++
    }
  }
  for (const m of xml.matchAll(/<programme\b[^>]*>[\s\S]*?<\/programme>/g)) {
    const open = m[0].slice(0, m[0].indexOf('>') + 1)
    const key = attr(open, 'channel') + '|' + attr(open, 'start')
    if (!programmes.has(key)) {
      programmes.set(key, m[0])
      p++
    }
  }
  console.log(`${file}: ${c} new channels, ${p} new programmes`)
}

const out = fs.createWriteStream(outFile)
out.write('<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="ChannelBox">\n')
for (const xml of channels.values()) out.write('  ' + xml + '\n')
for (const xml of programmes.values()) out.write('  ' + xml + '\n')
out.write('</tv>\n')
out.end()
out.on('finish', () => {
  console.log(`Merged ${files.length} files: ${channels.size} channels, ${programmes.size} programmes`)
  if (programmes.size === 0) process.exit(1)
})
