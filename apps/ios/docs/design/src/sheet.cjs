const {chromium}=require('playwright');const fs=require('fs');
const D='/data/out/ios-design';
const items=[['1.2-welcome','1.2 欢迎 / 未配对'],['1.3-scan','1.3 扫码'],['2.1-home','2.1 首页'],['4.1-chat-running','4.1 对话 · 运行中'],['4.3-chat-approval','4.3 对话 · 待审批'],['4.4-chat-question','4.4 对话 · 回答问题'],['6.1-changes','6.1 改动'],['6.2-diff','6.2 文件 diff'],['7.1-settings','7.1 设置'],['8.4-live-activity','8.4 锁屏 · Live Activity'],['8.5-dynamic-island','8.5 灵动岛状态']];
(async()=>{const b=await chromium.launch({executablePath:'/usr/local/bin/chromium'});
for(const th of ['light','dark']){const bg=th==='light'?'#EDEDF0':'#18181B',fg=th==='light'?'#111':'#eee';
let h=`<html><body style="margin:0;background:${bg};font-family:'Noto Sans SC';color:${fg};padding:40px 40px 20px;width:${6*330+40}px"><div style="font:700 30px 'Noto Sans SC';margin-bottom:28px">DeepLinks iOS 设计稿 · ${th==='light'?'浅色':'深色'}</div><div style="display:flex;flex-wrap:wrap;gap:30px 30px">`;
for(const [id,cap] of items){const src='data:image/png;base64,'+fs.readFileSync(`${D}/${id}--${th}.png`).toString('base64');
h+=`<div style="width:300px"><div style="position:relative;width:300px;height:652px;border-radius:44px;overflow:hidden;box-shadow:0 0 0 7px #111,0 0 0 8.5px #555,0 14px 34px rgba(0,0,0,.25)"><img src="${src}" style="width:300px;height:652px;display:block"><div style="position:absolute;top:8px;left:50%;transform:translateX(-50%);width:94px;height:28px;border-radius:14px;background:#000"></div></div><div style="margin-top:18px;font:600 15px 'Noto Sans SC'">${cap}</div></div>`}
h+='</div></body></html>';fs.writeFileSync('/data/tmp/sheet.html',h);
const p=await b.newPage({viewport:{width:6*330+120,height:800},deviceScaleFactor:1});await p.goto('file:///data/tmp/sheet.html');await p.screenshot({path:`${D}/_overview--${th}.png`,fullPage:true});await p.close()}
await b.close();process.exit(0)})().catch(e=>{console.error(e);process.exit(1)});
