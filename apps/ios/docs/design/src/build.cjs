const fs=require('fs');const I=require('./icons.cjs');
const W=402,H=874;
const css=`
*{box-sizing:border-box;margin:0;padding:0}
body{background:#888;font-family:-apple-system,"SF Pro Text","PingFang SC","Noto Sans SC",sans-serif;-webkit-font-smoothing:antialiased}
.screen{width:${W}px;height:${H}px;position:relative;overflow:hidden;background:var(--bg);color:var(--label);margin:20px;display:inline-block;vertical-align:top}
.light{--bg:#fff;--gbg:#F2F2F7;--cell:#fff;--bg2:#F2F2F7;--bg3:#fff;--label:#000;--l2:rgba(60,60,67,.6);--l3:rgba(60,60,67,.3);--sep:rgba(60,60,67,.18);--fill:rgba(120,120,128,.2);--fill2:rgba(120,120,128,.16);--fill3:rgba(118,118,128,.12);--accent:#3F5BD6;--brand:#3F5BD6;--orange:#FF9500;--green:#34C759;--red:#FF3B30;--gtext:#248A3D;--rtext:#D70015;--bubble:#E9E9EB;--glass:rgba(255,255,255,.62);--glassb:rgba(255,255,255,.75);--glasssh:0 8px 28px rgba(0,0,0,.12),0 0 0 .5px rgba(0,0,0,.06);--addbg:rgba(52,199,89,.14);--delbg:rgba(255,59,48,.12);--tint:rgba(63,91,214,.12);--codebg:#F2F2F7}
.dark{--bg:#000;--gbg:#000;--cell:#1C1C1E;--bg2:#1C1C1E;--bg3:#2C2C2E;--label:#fff;--l2:rgba(235,235,245,.6);--l3:rgba(235,235,245,.3);--sep:rgba(84,84,88,.55);--fill:rgba(120,120,128,.36);--fill2:rgba(120,120,128,.32);--fill3:rgba(118,118,128,.24);--accent:#8B9DFF;--brand:#4C66E6;--orange:#FF9F0A;--green:#30D158;--red:#FF453A;--gtext:#30D158;--rtext:#FF6961;--bubble:#262628;--glass:rgba(40,40,44,.55);--glassb:rgba(255,255,255,.14);--glasssh:0 8px 28px rgba(0,0,0,.5),0 0 0 .5px rgba(255,255,255,.08);--addbg:rgba(48,209,88,.13);--delbg:rgba(255,69,58,.16);--tint:rgba(139,157,255,.16);--codebg:#2C2C2E}
.abs{position:absolute}.row{display:flex;align-items:center}.col{display:flex;flex-direction:column}.f1{flex:1;min-width:0}
.sb{position:absolute;top:0;left:0;right:0;height:54px;z-index:50;font:600 17px/22px -apple-system,"Noto Sans SC";}
.sb .t{position:absolute;left:0;width:134px;top:17px;text-align:center;letter-spacing:-.2px}
.sb .ic{position:absolute;right:28px;top:20px;display:flex;gap:6px;align-items:center}
.hi{position:absolute;bottom:8px;left:50%;transform:translateX(-50%);width:139px;height:5px;border-radius:3px;background:var(--label);z-index:60}
.glass{background:var(--glass);backdrop-filter:blur(14px) saturate(180%);-webkit-backdrop-filter:blur(14px) saturate(180%);box-shadow:var(--glasssh),inset 0 .5px 0 var(--glassb);}
.gc{width:44px;height:44px;border-radius:22px;display:flex;align-items:center;justify-content:center;color:var(--label)}
.edge{position:absolute;left:0;right:0;top:0;height:110px;z-index:40;background:linear-gradient(var(--bg) 45%,transparent);pointer-events:none}
.edgeb{position:absolute;left:0;right:0;bottom:0;height:130px;z-index:40;background:linear-gradient(transparent,var(--bg) 62%)}
.lt{font:700 34px/41px -apple-system,"Noto Sans SC";letter-spacing:.2px}
.t3{font:600 20px/25px -apple-system,"Noto Sans SC"}
.hl{font:600 17px/22px -apple-system,"Noto Sans SC"}
.bd{font:400 17px/24px -apple-system,"Noto Sans SC"}
.sh{font:400 15px/20px -apple-system,"Noto Sans SC"}
.fn{font:400 13px/18px -apple-system,"Noto Sans SC"}
.cap{font:400 12px/16px -apple-system,"Noto Sans SC"}
.mono{font-family:"SF Mono",Menlo,"Liberation Mono","Noto Sans SC",monospace}
.l2{color:var(--l2)}.l3{color:var(--l3)}.acc{color:var(--accent)}
.sep{height:.5px;background:var(--sep)}
.dot{width:8px;height:8px;border-radius:4px;display:inline-block;flex:none}
.code{font-family:"SF Mono",Menlo,"Liberation Mono",monospace;font-size:15px;background:var(--fill3);border-radius:6px;padding:1px 5px}
.btn{height:34px;border-radius:17px;padding:0 14px;display:inline-flex;align-items:center;justify-content:center;font:600 15px/20px -apple-system,"Noto Sans SC";gap:6px}
.btn.gray{background:var(--fill2);color:var(--label)}
.btn.tint{background:var(--tint);color:var(--accent)}
.big{height:50px;border-radius:25px;font:600 17px/22px -apple-system,"Noto Sans SC";display:flex;align-items:center;justify-content:center;gap:8px}
.big.brand{background:var(--brand);color:#fff}
.big.gray{background:var(--fill2);color:var(--label)}
.chip{height:30px;border-radius:15px;background:var(--fill3);padding:0 9px;display:inline-flex;align-items:center;gap:4px;white-space:nowrap;font:500 12px/16px -apple-system,"Noto Sans SC";color:var(--label)}
.spin{animation:none}
.add{color:var(--gtext)}.del{color:var(--rtext)}
`;
const spinner=(s=16)=>{let r='';for(let i=0;i<8;i++){r+=`<rect x="${s/2-1}" y="1" width="2" height="${s*0.28}" rx="1" transform="rotate(${i*45} ${s/2} ${s/2})" fill="currentColor" opacity="${(0.25+i*0.1).toFixed(2)}"/>`}return `<svg width="${s}" height="${s}" style="flex:none;color:var(--l2)">${r}</svg>`};
const sbar=(white)=>`<div class="sb" style="${white?'color:#fff':''}"><div class="t">22:14</div><div class="ic">
<svg width="18" height="12" viewBox="0 0 18 12"><rect x="0" y="8" width="3" height="4" rx=".8" fill="currentColor"/><rect x="5" y="5.5" width="3" height="6.5" rx=".8" fill="currentColor"/><rect x="10" y="3" width="3" height="9" rx=".8" fill="currentColor"/><rect x="15" y="0" width="3" height="12" rx=".8" fill="currentColor"/></svg>
<svg width="16" height="12" viewBox="0 0 16 12"><path d="M8 11.5 5.6 9.1a3.4 3.4 0 0 1 4.8 0zM3.4 6.9a6.5 6.5 0 0 1 9.2 0l-1.1 1.1a5 5 0 0 0-7 0zM1.1 4.6a9.8 9.8 0 0 1 13.8 0l-1.1 1.1a8.2 8.2 0 0 0-11.6 0z" fill="currentColor"/></svg>
<svg width="27" height="13" viewBox="0 0 27 13"><rect x=".5" y=".5" width="23" height="12" rx="3.8" fill="none" stroke="currentColor" opacity=".4"/><rect x="2" y="2" width="17" height="9" rx="2.5" fill="currentColor"/><path d="M25 4.5v4c.8-.3 1.3-1.1 1.3-2s-.5-1.7-1.3-2z" fill="currentColor" opacity=".45"/></svg></div></div>`;
const hi=(c)=>`<div class="hi" style="${c?'background:'+c:''}"></div>`;
const gcirc=(icon,x,y,extra='')=>`<div class="abs glass gc" style="left:${x}px;top:${y}px;${extra}">${icon}</div>`;
const back=()=>gcirc(I('IoChevronBack',24),16,56);
const inlineNav=(title,sub,right)=>`<div class="edge"></div>${back()}
<div class="abs col" style="left:76px;right:${right?150:76}px;top:58px;z-index:45;align-items:center;text-align:center"><div class="hl" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%">${title}</div><div class="cap l2" style="white-space:nowrap">${sub}</div></div>
<div class="abs row glass" style="right:16px;top:56px;height:44px;border-radius:22px;z-index:45;padding:0 4px 0 ${right?14:4}px;gap:6px">${right||''}<div class="gc" style="width:36px">${I('IoEllipsisHorizontal',22)}</div></div>`;
const S={};

/* ---------- 2.1 Home ---------- */
S['2.1-home']=()=>`${sbar()}
<div class="edge" style="height:60px"></div>
${gcirc(I('IoSettingsOutline',22),342,56,'z-index:45')}
<div class="abs" style="left:20px;top:104px"><div class="lt">DeepLinks</div>
<div class="row sh l2" style="gap:6px;margin-top:2px"><span class="dot" style="background:var(--green)"></span>MacBook Pro · 在线 ${I('IoChevronDown',14)}</div></div>
<div class="abs" style="left:0;right:0;top:182px">
 <div class="row" style="padding:10px 20px 6px"><div class="hl f1">等你处理</div><div class="sh l2">2</div></div>
 <div style="padding:10px 20px 14px">
  <div class="row fn" style="gap:6px"><span class="dot" style="background:var(--orange)"></span><b style="font-weight:600">等你批准</b><span class="l2">· dsh-links</span><span class="f1"></span><span class="l2">2 分钟</span></div>
  <div class="hl" style="margin-top:4px">发布 beta.28 前跑一遍真机测试</div>
  <div class="mono" style="font-size:13px;background:var(--fill3);border-radius:8px;padding:7px 10px;margin-top:8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">./gradlew :app:connectedDebugAndroidTest</div>
  <div class="row" style="justify-content:flex-end;gap:8px;margin-top:10px"><span class="btn gray">拒绝</span><span class="btn tint">允许一次</span></div>
 </div>
 <div class="sep" style="margin-left:20px"></div>
 <div style="padding:12px 20px 14px">
  <div class="row fn" style="gap:6px"><span class="dot" style="background:var(--orange)"></span><b style="font-weight:600">等你回答</b><span class="l2">· relay</span><span class="f1"></span><span class="l2">8 分钟</span></div>
  <div class="hl" style="margin-top:4px">中继限流策略</div>
  <div class="sh l2" style="margin-top:2px">问：每台设备每分钟上限设成 60 还是 120？</div>
  <div class="row" style="justify-content:flex-end;margin-top:8px"><span class="btn gray">回答</span></div>
 </div>
 <div class="row" style="padding:18px 20px 6px"><div class="hl f1">进行中</div><div class="sh l2">2</div></div>
 <div style="padding:10px 20px 12px">
  <div class="row fn l2"><span>dsh-links</span><span class="f1"></span><span>3 分钟</span></div>
  <div class="hl" style="margin-top:3px">完善审批状态同步</div>
  <div class="row sh l2" style="gap:8px;margin-top:3px">${spinner(15)}<span>正在运行 go test ./… · 第 12 步</span></div>
 </div>
 <div class="sep" style="margin-left:20px"></div>
 <div style="padding:12px 20px 12px">
  <div class="row fn l2"><span>dsh-links · 目标 3/8 轮</span><span class="f1"></span><span>21 分钟</span></div>
  <div class="hl" style="margin-top:3px">把文本反推 UI 改成结构化字段</div>
  <div class="row sh l2" style="gap:8px;margin-top:3px">${spinner(15)}<span>正在写回复</span></div>
 </div>
 <div class="row" style="padding:18px 20px 6px"><div class="hl f1">最近</div></div>
 <div style="padding:10px 20px">
  <div class="row fn" style="gap:6px"><span class="dot" style="background:var(--green)"></span><b style="font-weight:600">完成</b><span class="l2">· dsh-links</span><span class="f1"></span><span class="l2">1 小时</span></div>
  <div class="hl" style="margin-top:3px">修复 goal round 崩溃</div>
  <div class="sh l2">改了 4 个文件 · 耗时 14 分钟</div>
 </div>
</div>
<div class="edgeb"></div><div class="abs row" style="left:16px;right:16px;bottom:30px;gap:10px;z-index:45">
 <div class="glass row f1" style="height:48px;border-radius:24px;padding:0 16px;gap:8px;color:var(--l2)">${I('IoSearch',20)}<span class="bd f1" style="color:var(--l2)">搜索</span>${I('IoMic',20)}</div>
 <div class="row" style="width:48px;height:48px;border-radius:24px;background:var(--brand);color:#fff;justify-content:center;box-shadow:var(--glasssh)">${I('IoCreateOutline',24)}</div>
</div>${hi()}`;

/* ---------- chat shared ---------- */
const transcript=(fade)=>`<div style="${fade?'opacity:.42':''}">
 <div class="row" style="margin:0 16px;background:var(--bg2);border-radius:18px;padding:11px 14px;gap:10px">${I('IoLocateOutline',20,'var(--l2)')}<div class="f1"><div class="sh" style="font-weight:600">目标 · 第 3/8 轮 · 计划 4/7</div><div class="fn l2">正在：补审批过期的单测</div></div>${I('IoChevronDown',16,'var(--l2)')}</div>
 <div class="row" style="justify-content:flex-end;margin:16px 16px 0"><div class="bd" style="background:var(--bubble);border-radius:20px;padding:10px 14px;max-width:290px">审批状态在手机和电脑之间不同步，电脑上点了允许，手机还显示「等你批准」。</div></div>
 <div class="row fn l2" style="gap:5px;margin:16px 20px 0">${I('IoSparklesOutline',14)}思考 6 秒 · 读了 4 个文件 ${I('IoChevronForward',12)}</div>
 <div class="bd" style="margin:8px 20px 0">原因是 <span class="code">approval.resolved</span> 事件只推给了发起审批的连接。我会让插件在任一端处理后广播给所有订阅者，App 收到后把那条审批标为已处理。</div>
 <div class="row fn l2" style="gap:5px;margin:12px 20px 0">${I('IoTerminalOutline',14)}运行了 3 个命令 · 改了 2 个文件 ${I('IoChevronForward',12)}</div>
 <div class="row sh l2" style="gap:8px;margin:10px 20px 0">${spinner(15)}正在运行 <span class="mono" style="font-size:13px">go test ./…</span></div>
</div>`;
const diffBadge=`<span class="mono fn" style="font-weight:600"><span class="add">+148</span> <span class="del">−37</span></span>`;

S['4.1-chat-running']=()=>`${sbar()}${inlineNav('完善审批状态同步','dsh-links · 运行中 · 第 12 步',diffBadge)}
<div class="abs" style="left:0;right:0;top:112px">${transcript(false)}</div>
<div class="abs glass" style="left:12px;right:12px;bottom:26px;border-radius:28px;padding:14px 12px 10px 16px;z-index:45">
 <div class="bd l3" style="padding:0 2px 12px">补充说明，这一步结束后发给它</div>
 <div class="row" style="gap:6px"><div class="row" style="width:30px;height:30px;border-radius:15px;background:var(--fill3);justify-content:center">${I('IoAdd',20)}</div>
 <span class="chip">${I('IoHardwareChipOutline',14)}step-5-preview · 高</span><span class="chip">${I('IoShieldOutline',14)}工作区内修改</span><span class="f1"></span>
 <div class="row" style="width:36px;height:36px;border-radius:18px;background:var(--label);justify-content:center">${I('IoStop',14,'var(--bg)')}</div></div>
</div>${hi()}`;

S['4.3-chat-approval']=()=>`${sbar()}${inlineNav('完善审批状态同步','dsh-links · 等你批准',diffBadge)}
<div class="abs" style="left:0;right:0;top:112px">${transcript(true)}</div>
<div class="abs glass" style="left:12px;right:12px;bottom:26px;border-radius:32px;padding:18px 16px 16px;z-index:45">
 <div class="row fn" style="gap:6px"><span class="dot" style="background:var(--orange)"></span><b style="font-weight:600">等你批准</b><span class="f1"></span><span class="l2">2 分钟前</span></div>
 <div class="t3" style="margin-top:6px">要运行这个命令吗？</div>
 <div class="mono" style="font-size:13px;line-height:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;background:var(--codebg);border-radius:14px;padding:12px 14px;margin-top:12px">./gradlew :app:connectedDebugAndroidTest</div>
 <div class="fn l2" style="margin-top:10px">dsh-links · 在电脑上执行 · 权限：工作区内修改</div>
 <div class="row" style="gap:10px;margin-top:16px"><div class="big gray f1">拒绝</div><div class="big brand f1">允许一次</div></div>
</div>${hi()}`;

S['4.4-chat-question']=()=>`${sbar()}${inlineNav('中继限流策略','relay · 等你回答','')}
<div class="abs" style="left:0;right:0;top:120px;opacity:.42">
 <div class="row" style="justify-content:flex-end;margin:0 16px"><div class="bd" style="background:var(--bubble);border-radius:20px;padding:10px 14px;max-width:290px">给中继加上按设备的限流。</div></div>
 <div class="bd" style="margin:16px 20px 0">限流我准备按设备 token 计数，动手前先确认两个参数。</div>
</div>
<div class="abs glass" style="left:12px;right:12px;bottom:26px;border-radius:32px;padding:18px 16px 16px;z-index:45">
 <div class="row fn" style="gap:6px"><span class="dot" style="background:var(--orange)"></span><b style="font-weight:600">等你回答</b><span class="f1"></span><span class="l2">问题 1/2</span></div>
 <div class="t3" style="margin-top:6px">每台设备每分钟的请求上限设成多少？</div>
 <div style="background:var(--bg3);border-radius:18px;margin-top:12px;overflow:hidden">
  <div class="row bd" style="padding:13px 14px;gap:10px"><span class="f1">60 次<span class="l2 sh">（推荐，与现在的面板一致）</span></span>${I('IoCheckmark',22,'var(--accent)')}</div>
  <div class="sep" style="margin-left:14px"></div>
  <div class="row bd" style="padding:13px 14px;gap:10px"><span class="f1">120 次</span></div>
  <div class="sep" style="margin-left:14px"></div>
  <div class="row bd l3" style="padding:13px 14px;gap:8px">${I('IoPencil',16)}<span class="f1">自己写答案</span></div>
 </div>
 <div class="row" style="gap:10px;margin-top:16px"><div class="big gray f1">跳过</div><div class="big brand f1">下一题</div></div>
</div>${hi()}`;

/* ---------- 6.1 changes ---------- */
const frow=(n,p,a,d)=>`<div class="row" style="padding:12px 20px;gap:12px">${I('IoDocumentTextOutline',22,'var(--l2)')}<div class="f1"><div class="mono" style="font-size:15px;line-height:20px">${n}</div><div class="fn l2">${p}</div></div><span class="mono fn" style="font-weight:600"><span class="add">+${a}</span> <span class="del">−${d}</span></span>${I('IoChevronForward',14,'var(--l3)')}</div><div class="sep" style="margin-left:54px"></div>`;
S['6.1-changes']=()=>`${sbar()}${inlineNav('改动','4 个文件 · +62 −9','')}
<div class="abs row" style="left:16px;right:16px;top:114px;height:36px;border-radius:18px;background:var(--fill3);padding:2px">
 <div class="f1 row sh" style="height:32px;border-radius:16px;background:var(--bg3);justify-content:center;font-weight:600;box-shadow:0 2px 6px rgba(0,0,0,.12)">本轮</div><div class="f1 row sh" style="justify-content:center">整个会话</div><div class="f1 row sh" style="justify-content:center">未提交</div></div>
<div class="abs" style="left:0;right:0;top:166px">
${frow('host-events.js','src/',31,4)}${frow('SessionStreamClient.kt','apps/android/…/native/',18,5)}${frow('host-events.test.mjs','test/',12,0)}${frow('MOBILE_SYNC_CONTRACT.md','docs/',1,0)}
<div class="fn l2" style="padding:12px 20px">点文件看 diff。改动只读，回滚在电脑上做。</div></div>
<div class="abs big brand" style="left:20px;right:20px;bottom:34px;z-index:45">${I('IoChatbubbleEllipsesOutline',20,'#fff')}就这些改动提问</div>${hi()}`;

/* ---------- 6.2 diff ---------- */
const dl=(n,t,txt)=>{const bg=t==='+'?'var(--addbg)':t==='-'?'var(--delbg)':t==='@'?'var(--fill3)':'transparent';const c=t==='@'?'var(--l2)':'var(--label)';
return `<div class="row mono" style="font-size:12.5px;line-height:21px;background:${bg};color:${c};white-space:pre"><span style="width:40px;text-align:right;padding-right:8px;color:var(--l3);flex:none">${n}</span><span style="width:14px;flex:none;color:${t==='+'?'var(--gtext)':t==='-'?'var(--rtext)':'var(--l3)'}">${t==='@'||t===' '?'':t}</span><span>${txt.replace(/</g,'&lt;')}</span></div>`};
S['6.2-diff']=()=>`${sbar()}${inlineNav('host-events.js','src/ · +31 −4','')}
<div class="abs" style="left:0;right:0;top:112px">
${dl('','@','@@ -41,9 +41,22 @@ export function')}${dl(41,' ','  const subs = rt.subscribers')}${dl(42,'-','  emit(origin, evt)')}${dl(42,'+','  for (const s of subs) {')}${dl(43,'+','    s.send({ type:')}${dl(44,'+','      "approval.resolved",')}${dl(45,'+','      id: evt.id })')}${dl(46,'+','  }')}${dl(47,' ','  log.debug("resolved")')}${dl(48,' ','}')}
${dl('','@','@@ -88,4 +101,13 @@')}${dl(101,' ','export function prune(m) {')}${dl(102,'-','  // TODO: expire')}${dl(102,'+','  const now = Date.now()')}${dl(103,'+','  for (const [k,v] of m) {')}${dl(104,'+','    if (v.exp < now) m.delete(k)')}${dl(105,'+','  }')}${dl(106,' ','}')}
${dl('','@','@@ -130,6 +152,8 @@ function subscribe')}${dl(152,' ','  rt.subscribers.add(ws)')}${dl(153,'+','  ws.on("close", () =>')}${dl(154,'+','    rt.subscribers.delete(ws))')}${dl(155,' ','  return ws')}
</div>
<div class="abs row" style="left:16px;right:16px;bottom:30px;gap:10px;z-index:45">
 <div class="glass row" style="height:50px;border-radius:25px;padding:0 6px">${'<div class="gc" style="width:46px">'+I('IoChevronUp',22)+'</div><div class="gc" style="width:46px">'+I('IoChevronDown',22)+'</div>'}</div><span class="f1"></span>
 <div class="big brand" style="padding:0 20px">${I('IoChatbubbleEllipsesOutline',20,'#fff')}就这段提问</div></div>${hi()}`;

/* ---------- 7.1 settings ---------- */
const srow=(ic,t,v,last)=>`<div class="row" style="min-height:52px;padding:0 16px;gap:14px">${I(ic,22,'var(--accent)')}<div class="row f1" style="align-self:stretch;${last?'':'box-shadow:inset 0 -.5px 0 var(--sep)'}"><span class="bd f1">${t}</span><span class="bd l2" style="margin-right:6px">${v}</span>${I('IoChevronForward',14,'var(--l3)')}</div></div>`;
const grp=(h,rows)=>`<div class="fn l2" style="padding:22px 36px 7px">${h}</div><div style="margin:0 16px;background:var(--cell);border-radius:26px;overflow:hidden">${rows}</div>`;
S['7.1-settings']=()=>`<div class="abs" style="inset:0;background:var(--gbg)"></div>${sbar()}${back()}
<div class="abs lt" style="left:20px;top:104px">设置</div>
<div class="abs" style="left:0;right:0;top:160px">
 <div class="row" style="margin:0 16px;background:var(--cell);border-radius:26px;padding:14px 16px;gap:14px"><div class="row" style="width:44px;height:44px;border-radius:12px;background:var(--tint);justify-content:center">${I('IoLaptopOutline',26,'var(--accent)')}</div><div class="f1"><div class="hl">MacBook Pro</div><div class="row fn l2" style="gap:6px"><span class="dot" style="background:var(--green)"></span>在线 · 局域网 · Tailscale 备用</div></div>${I('IoChevronForward',14,'var(--l3)')}</div>
 ${grp('通用',srow('IoLanguageOutline','语言','中文')+srow('IoNotificationsOutline','通知','审批、完成')+srow('IoContrastOutline','外观','跟随系统',1))}
 ${grp('智能体',srow('IoOptionsOutline','对话默认','工作区内修改')+srow('IoWalletOutline','模型与余额','¥42.10',1))}
 ${grp('其他',srow('IoArchiveOutline','会话记录','6')+srow('IoInformationCircleOutline','关于','0.6.0',1))}
 <div class="fn l2" style="padding:12px 36px">DeepLinks 是独立的社区项目，与 DeepSeek 无隶属关系。</div>
</div>${hi()}`;

/* ---------- 1.2 welcome ---------- */
const appIcon=(s)=>`<div class="row" style="width:${s}px;height:${s}px;border-radius:${s*0.225}px;background:linear-gradient(160deg,#5A74E8,#3F5BD6 60%,#3550C4);justify-content:center;color:#fff;box-shadow:inset 0 1px 0 rgba(255,255,255,.25)"><span class="mono" style="font-size:${s*0.38}px;font-weight:700;letter-spacing:-1px">&gt;_</span></div>`;
const step=(n,t,s)=>`<div class="row" style="gap:14px;align-items:flex-start;margin-top:22px"><div class="row" style="width:30px;height:30px;border-radius:15px;background:var(--tint);justify-content:center;flex:none" ><span class="sh acc" style="font-weight:600">${n}</span></div><div><div class="bd" style="font-weight:500">${t}</div><div class="sh l2">${s}</div></div></div>`;
S['1.2-welcome']=()=>`${sbar()}
<div class="abs" style="left:28px;right:28px;top:120px">${appIcon(72)}
 <div class="lt" style="margin-top:28px">把电脑上的 DSH<br>放进口袋</div>
 <div class="bd l2" style="margin-top:10px">审批、回答、看进度，不用守在电脑前。</div>
 <div style="margin-top:20px">${step(1,'在电脑上打开 dsh','运行 dsh，进入「手机连接」面板')}${step(2,'扫描面板上的二维码','同一网络，或两边都装了 Tailscale')}${step(3,'在电脑上点「批准」','之后自动重连，不用再扫')}</div>
</div>
<div class="abs" style="left:20px;right:20px;bottom:44px">
 <div class="big brand" style="height:54px;border-radius:27px">${I('IoQrCodeOutline',22,'#fff')}扫码配对</div>
 <div class="row hl acc" style="justify-content:center;gap:36px;margin-top:18px;font-weight:500"><span>从相册识别</span><span>输入配对码</span></div>
 <div class="row sh l2" style="justify-content:center;margin-top:16px">先看看演示</div>
 <div class="cap l3" style="text-align:center;margin-top:18px">非官方社区项目 · 与 DeepSeek 无隶属关系</div>
</div>${hi()}`;

/* ---------- 1.3 scan ---------- */
const corner=(r)=>`<svg width="250" height="250" viewBox="0 0 250 250" style="position:absolute;inset:0"><g fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round"><path d="M3 60V36A33 33 0 0 1 36 3h24"/><path d="M190 3h24a33 33 0 0 1 33 33v24"/><path d="M247 190v24a33 33 0 0 1-33 33h-24"/><path d="M60 247H36A33 33 0 0 1 3 214v-24"/></g></svg>`;
S['1.3-scan']=(th)=>`<div class="abs" style="inset:0;background:radial-gradient(120% 70% at 30% 35%,#4a4f57,#23262b 55%,#0d0e10);"></div>
<div class="abs" style="left:70px;top:330px;width:260px;height:190px;border-radius:10px;background:linear-gradient(135deg,#2c3340,#1b1f26);filter:blur(1.5px);opacity:.9"></div>
<div class="abs" style="left:-40px;top:610px;width:500px;height:330px;background:linear-gradient(#3a3227,#1a1612);filter:blur(3px);transform:rotate(-4deg)"></div>
${sbar(true)}
<div class="abs glass gc" style="left:16px;top:56px;color:#fff;background:rgba(60,60,64,.45)">${I('IoClose',24,'#fff')}</div>
<div class="abs glass gc" style="right:16px;top:56px;color:#fff;background:rgba(60,60,64,.45)">${I('IoFlashlightOutline',22,'#fff')}</div>
<div class="abs hl" style="left:0;right:0;top:248px;text-align:center;color:#fff">扫描电脑上的配对二维码</div>
<div class="abs" style="left:76px;top:296px;width:250px;height:250px">${corner()}</div>
<div class="abs fn" style="left:0;right:0;top:568px;text-align:center;color:rgba(255,255,255,.75)">二维码在 dsh 的「手机连接」面板里</div>
<div class="abs row" style="left:0;right:0;bottom:44px;justify-content:center">
 <div class="glass row" style="height:50px;border-radius:25px;padding:0 6px;background:rgba(60,60,64,.45);color:#fff">
  <div class="row sh" style="gap:7px;padding:0 14px;font-weight:600">${I('IoImagesOutline',19,'#fff')}从相册选择</div><div style="width:.5px;height:22px;background:rgba(255,255,255,.3)"></div><div class="row sh" style="gap:7px;padding:0 14px;font-weight:600">${I('IoKeypadOutline',19,'#fff')}输入配对码</div></div></div>${hi('#fff')}`;

/* ---------- 8.x Live Activity (lock screen) ---------- */
S['8.4-live-activity']=(th)=>{const d=th==='dark';const card=d?'rgba(30,30,34,.55)':'rgba(255,255,255,.55)';const tx=d?'#fff':'#000';const t2=d?'rgba(235,235,245,.6)':'rgba(60,60,67,.65)';
return `<div class="abs" style="inset:0;background:${d?'radial-gradient(90% 60% at 70% 20%,#2b3a8f,#141a3d 55%,#07080f)':'radial-gradient(90% 60% at 70% 20%,#c9d4ff,#a7b6f2 50%,#7f8fd6)'}"></div>
${sbar(true)}
<div class="abs" style="left:0;right:0;top:78px;text-align:center;color:#fff"><div style="font:600 21px/26px -apple-system,'Noto Sans SC';opacity:.92">10月2日 星期五</div><div style="font:600 104px/112px -apple-system,'Noto Sans SC';letter-spacing:-2px;margin-top:2px">22:14</div></div>
<div class="abs" style="left:12px;right:12px;top:470px;background:${card};backdrop-filter:blur(24px) saturate(160%);border-radius:24px;padding:12px 14px;color:${tx}">
 <div class="row" style="gap:10px">${appIcon(38)}<div class="f1"><div class="row fn" style="color:${t2}"><span class="f1">DeepLinks</span><span>现在</span></div><div class="sh" style="font-weight:600">有一项操作等待确认</div><div class="sh" style="color:${t2}">解锁后在 App 内确认</div></div></div></div>
<div class="abs" style="left:12px;right:12px;top:568px;background:${card};backdrop-filter:blur(24px) saturate(160%);border-radius:26px;padding:16px;color:${tx}">
 <div class="row" style="gap:10px">${appIcon(26)}<span class="fn" style="font-weight:600;color:${t2}">DeepLinks</span><span class="f1"></span><span class="row fn" style="gap:6px;color:${t2}">${spinner(13).replace('var(--l2)',t2)}进行中</span></div>
 <div class="hl" style="margin-top:12px">完善审批状态同步</div>
 <div class="row fn" style="margin-top:2px;color:${t2}"><span class="f1">dsh-links · 第 12 步</span><span class="mono">03:12</span></div>
 <div style="height:6px;border-radius:3px;background:${d?'rgba(255,255,255,.18)':'rgba(0,0,0,.1)'};margin-top:12px"><div style="width:42%;height:6px;border-radius:3px;background:${d?'#8B9DFF':'#3F5BD6'}"></div></div>
</div>
<div class="abs glass gc" style="left:46px;bottom:46px;width:50px;height:50px;border-radius:25px;background:rgba(0,0,0,.25)">${I('IoFlashlight',22,'#fff')}</div>
<div class="abs glass gc" style="right:46px;bottom:46px;width:50px;height:50px;border-radius:25px;background:rgba(0,0,0,.25)">${I('IoCamera',22,'#fff')}</div>${hi('#fff')}`};

/* ---------- Dynamic Island states (review sheet, not full-screen) ---------- */
S['8.5-dynamic-island']=(th)=>{const d=th==='dark';const ink='#fff';
const blk=(x)=>`<div style="background:#000;color:${ink};box-shadow:0 0 0 .5px rgba(255,255,255,.12);${x}">`;
return `<div class="abs" style="inset:0;background:${d?'linear-gradient(#24262d,#121318)':'var(--gbg)'}"></div>${sbar()}
<div class="abs" style="left:0;right:0;top:78px">
 <div class="fn l2" style="padding:0 24px 10px">紧凑态 · 运行中</div>
 <div class="row" style="justify-content:center">${blk('width:250px;height:37px;border-radius:19px;display:flex;align-items:center;padding:0 12px;gap:8px')}${appIcon(22)}<span class="f1"></span><span class="mono fn" style="color:#8B9DFF;font-weight:600">3/8</span></div></div>
 <div class="fn l2" style="padding:26px 24px 10px">紧凑态 · 等你批准（只显示状态，不显示命令）</div>
 <div class="row" style="justify-content:center">${blk('width:250px;height:37px;border-radius:19px;display:flex;align-items:center;padding:0 12px;gap:8px')}${appIcon(22)}<span class="f1"></span><span class="dot" style="background:#FF9F0A;width:10px;height:10px;border-radius:5px"></span></div></div>
 <div class="fn l2" style="padding:26px 24px 10px">最小态（与其他活动并存）</div>
 <div class="row" style="justify-content:center;gap:8px">${blk('width:126px;height:37px;border-radius:19px')}</div>${blk('width:37px;height:37px;border-radius:19px;display:flex;align-items:center;justify-content:center')}${appIcon(22)}</div></div>
 <div class="fn l2" style="padding:26px 24px 10px">展开态（长按）</div>
 <div class="row" style="justify-content:center">${blk('width:378px;border-radius:44px;padding:18px 20px 18px')}
  <div class="row" style="gap:10px">${appIcon(34)}<div class="f1"><div class="hl">完善审批状态同步</div><div class="fn" style="color:rgba(235,235,245,.6)">dsh-links · 第 12 步 · 03:12</div></div><span class="row fn" style="gap:6px;color:#FF9F0A;font-weight:600"><span class="dot" style="background:#FF9F0A"></span>等你批准</span></div>
  <div style="height:6px;border-radius:3px;background:rgba(255,255,255,.18);margin-top:16px"><div style="width:42%;height:6px;border-radius:3px;background:#8B9DFF"></div></div>
  <div class="row" style="margin-top:14px;gap:10px"><div class="big f1" style="height:44px;background:#4C66E6;color:#fff">打开 App 审批</div></div>
 </div></div>
 <div class="fn l2" style="padding:22px 24px;line-height:20px">推送只带：状态、步数、计时、进度。任务标题由 App 按会话 ID 在本机查到后显示；命令、文件名、正文都不进推送。灵动岛与锁屏都不提供“允许”按钮。</div>
</div>${hi()}`};

/* ---------- BrandFill candidates ---------- */
S['brandfill-candidates']=()=>{const c=[['#4F6AEB','4.57'],['#4C66E6','4.83 · 推荐'],['#4A63E0','5.06'],['#3F5BD6','5.71 · 与浅色同色']];
return `${sbar()}<div class="abs lt" style="left:20px;top:100px">深色主按钮</div><div class="abs sh l2" style="left:20px;right:20px;top:146px">白字对比度都 ≥ 4.5:1。在 iPhone 深色模式、亮度中等时全屏看，选最舒服的一个。</div>
<div class="abs" style="left:20px;right:20px;top:210px">${c.map(([h,r])=>`<div style="margin-bottom:22px"><div class="row fn l2" style="margin-bottom:8px"><span class="mono f1">${h}</span><span>${r}</span></div><div class="row" style="gap:10px"><div class="big gray f1">拒绝</div><div class="big f1" style="background:${h};color:#fff">允许一次</div></div></div>`).join('')}
<div class="fn l2" style="margin-top:6px">参照：强调色（文字、图标）</div><div class="row hl" style="gap:24px;margin-top:8px;color:#8B9DFF"><span>从相册识别</span><span>输入配对码</span>${I('IoSettingsOutline',22,'#8B9DFF')}</div></div>${hi()}`};

const order=['1.2-welcome','1.3-scan','2.1-home','4.1-chat-running','4.3-chat-approval','4.4-chat-question','6.1-changes','6.2-diff','7.1-settings','8.4-live-activity','8.5-dynamic-island','brandfill-candidates'];
let html=`<!doctype html><html><head><meta charset="utf-8"><style>${css}</style></head><body>`;
for(const k of order){for(const th of ['light','dark']){if(k==='brandfill-candidates'&&th==='light')continue;html+=`<section class="screen ${th}" id="${k}--${th}">${S[k](th)}</section>`}}
html+='</body></html>';fs.writeFileSync(__dirname+'/screens.html',html);console.log('built',order.length);
