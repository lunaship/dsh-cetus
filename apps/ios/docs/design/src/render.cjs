const {chromium}=require('playwright');const fs=require('fs');
(async()=>{const b=await chromium.launch({executablePath:'/usr/local/bin/chromium'});
const p=await b.newPage({deviceScaleFactor:3,viewport:{width:1000,height:1000}});
await p.goto('file://'+__dirname+'/screens.html');await p.waitForTimeout(500);
const out='/data/out/ios-design';fs.mkdirSync(out,{recursive:true});
for(const id of await p.$$eval('section',s=>s.map(x=>x.id))){await p.locator(`[id="${id}"]`).screenshot({path:`${out}/${id}.png`});}
await b.close();process.exit(0)})().catch(e=>{console.error(e);process.exit(1)});
