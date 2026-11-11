// Reproduce the shipped icons from the pinned Lucide sources. Requires sharp.
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');
const commit = '1ef60af944de207e0d5176905a0759ba12e6ee8d';
const names = {
  'arrow-counter-clockwise':'rotate-ccw','arrow-left':'arrow-left','arrow-up':'arrow-up',
  'arrows-clockwise':'refresh-cw','arrows-out':'expand','article':'file-text',
  'caret-down':'chevron-down','clipboard':'clipboard','clock':'clock','copy':'copy',
  'dots-three':'ellipsis','download-simple':'download','eye':'eye','file-code':'file-code',
  'file':'file','film-strip':'film','floppy-disk':'save','folder-open':'folder-open','folder':'folder',
  'gear':'settings','headphones':'headphones','heart':'star','image':'image','info':'info',
  'magnifying-glass':'search','pause':'pause','pencil-simple':'pencil','play':'play','plus':'plus',
  'scissors':'scissors','sidebar-simple':'panel-left','tag':'tag','trash':'trash',
  'upload-simple':'upload','x':'x',
  'server':'server','activity':'activity','terminal':'terminal','alert-triangle':'triangle-alert',
  'hard-drive':'hard-drive','power':'power',
  'book-open':'book-open','captions':'captions','cloud-download':'cloud-download','fast-forward':'fast-forward',
  'gauge':'gauge','list-video':'list-video','maximize':'maximize','minimize':'minimize','minus':'minus',
  'audio-lines':'audio-lines','picture-in-picture':'picture-in-picture','rewind':'rewind','square':'square','type':'type',
  'volume-1':'volume-1','volume-2':'volume-2','volume-x':'volume-x',
};
const base = `https://raw.githubusercontent.com/lucide-icons/lucide/${commit}`;
const root = path.resolve(__dirname, '../src/Fileway.App/Assets/Lucide');
async function download(relative) {
  const response = await fetch(`${base}/${relative}`);
  if (!response.ok) throw new Error(`Lucide source ${relative}: ${response.status}`);
  return response.text();
}
(async () => {
  for (const sub of ['source','light','dark']) await fs.mkdir(path.join(root, sub),{recursive:true});
  const requested = process.argv.slice(2);
  for (const name of requested)
    if (!Object.hasOwn(names, name)) throw new Error(`Unknown Lucide alias: ${name}`);
  const entries = Object.entries(names).filter(([name]) => requested.length === 0 || requested.includes(name));
  for (let i=0;i<entries.length;i+=5) await Promise.all(entries.slice(i,i+5).map(async ([alias,name]) => {
    const svg = await download(`icons/${name}.svg`);
    await fs.writeFile(path.join(root,'source',`${alias}.svg`),svg);
    for (const [theme,color] of [['light','#292D33'],['dark','#EBEEF2']]) {
      await sharp(Buffer.from(svg.replaceAll('currentColor',color)),{density:1536}).resize(512,512)
        .png().toFile(path.join(root,theme,`${alias}.png`));
    }
  }));
  if (requested.length === 0) {
    await fs.writeFile(path.resolve(__dirname,'../licenses/Lucide-LICENSE.txt'),await download('LICENSE'));
    await fs.writeFile(path.resolve(__dirname,'../licenses/lucide-icons.md'),
    `# Lucide Icons\n\nSource: https://github.com/lucide-icons/lucide/tree/${commit}\n\nOriginal SVG strokes are rendered to transparent PNG for native ImageIcon support. Light and dark variants preserve the original geometry and stroke width. Reproduce with eng/Import-LucideIcons.cjs and sharp. ISC / MIT notices are in Lucide-LICENSE.txt.\n`);
  }
  console.log('Imported Lucide icons: '+entries.length);
})().catch(error => { console.error(error); process.exitCode=1; });
