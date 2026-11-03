package com.puli.islanddrop

/** The page the iPhone sees in Safari after scanning the QR code. Styled like an iOS share sheet. */
object WebPage {
    fun html(device: String): String {
        val name = device.replace("<", "&lt;").replace("&", "&amp;")
        return TEMPLATE.replace("{{DEVICE}}", name)
    }

    private val TEMPLATE = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="theme-color" content="#f2f2f7" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#000000" media="(prefers-color-scheme: dark)">
<title>{{DEVICE}} · Island Drop</title>
<style>
:root{--bg:#f2f2f7;--card:#fff;--label:#000;--sec:#8a8a8e;--sep:#e5e5ea;--blue:#007aff;--green:#34c759;--fill:#e9e9eb}
@media (prefers-color-scheme:dark){:root{--bg:#000;--card:#1c1c1e;--label:#fff;--sec:#98989f;--sep:#38383a;--blue:#0a84ff;--fill:#2c2c2e}}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:var(--bg);color:var(--label);font:17px/1.3 -apple-system,BlinkMacSystemFont,sans-serif;
 padding:env(safe-area-inset-top) 16px calc(env(safe-area-inset-bottom) + 24px)}
.hero{text-align:center;padding:34px 0 22px}
.dev{width:84px;height:84px;margin:0 auto 14px;border-radius:50%;background:linear-gradient(#8e9aaf,#5b6475);display:flex;
 align-items:center;justify-content:center;position:relative;animation:pop .7s cubic-bezier(.2,1.5,.4,1)}
.dev:before,.dev:after{content:"";position:absolute;inset:-10px;border-radius:50%;border:2px solid var(--blue);opacity:0;animation:ring 2.4s infinite}
.dev:after{animation-delay:1.2s}
@keyframes ring{0%{transform:scale(.85);opacity:.6}100%{transform:scale(1.35);opacity:0}}
@keyframes pop{from{transform:scale(.4);opacity:0}}
h1{font-size:28px;margin:0;font-weight:700;letter-spacing:-.3px}
.sub{color:var(--sec);font-size:15px;margin-top:4px}
.sub b{color:var(--green);font-weight:600}
.btn{display:flex;align-items:center;justify-content:center;gap:8px;width:100%;height:54px;border:0;border-radius:15px;
 background:var(--blue);color:#fff;font:600 17px -apple-system;transition:transform .15s}
.btn:active{transform:scale(.97);opacity:.85}
h2{font-size:13px;font-weight:400;color:var(--sec);text-transform:uppercase;margin:28px 16px 8px;letter-spacing:.2px}
.card{background:var(--card);border-radius:14px;overflow:hidden}
.row{display:flex;align-items:center;gap:12px;padding:12px 14px;border-top:.5px solid var(--sep);animation:in .45s cubic-bezier(.2,1.3,.4,1)}
.row:first-child{border-top:0}
@keyframes in{from{transform:translateY(12px);opacity:0}}
.ico{width:40px;height:48px;border-radius:7px;flex:none;display:flex;align-items:flex-end;justify-content:center;color:#fff;
 font:700 10px -apple-system;padding-bottom:6px;position:relative;background:var(--blue)}
.ico:after{content:"";position:absolute;top:0;right:0;border-style:solid;border-width:0 11px 11px 0;border-color:transparent var(--card) rgba(255,255,255,.45) transparent}
.meta{flex:1;min-width:0}
.name{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:16px}
.size{color:var(--sec);font-size:13px;margin-top:2px}
.pill{border:0;border-radius:15px;padding:7px 14px;background:var(--fill);color:var(--blue);font:600 15px -apple-system;text-decoration:none;white-space:nowrap}
.bar{height:4px;border-radius:2px;background:var(--fill);margin-top:7px;overflow:hidden}
.bar i{display:block;height:100%;width:0;background:var(--blue);border-radius:2px;transition:width .2s}
.done{color:var(--green);font-weight:600;font-size:15px}
.empty{padding:22px 16px;text-align:center;color:var(--sec);font-size:15px}
input{display:none}
.toast{position:fixed;left:50%;bottom:calc(env(safe-area-inset-bottom) + 24px);transform:translate(-50%,120px);background:rgba(40,40,40,.92);
 color:#fff;padding:12px 20px;border-radius:22px;font-size:15px;transition:transform .45s cubic-bezier(.2,1.4,.4,1);backdrop-filter:blur(20px)}
.toast.on{transform:translate(-50%,0)}
</style></head><body>
<div class="hero">
  <div class="dev"><svg width="38" height="38" viewBox="0 0 24 24" fill="#fff"><rect x="5" y="2" width="14" height="20" rx="3"/><rect x="7" y="4.5" width="10" height="15" rx="1" fill="#5b6475"/></svg></div>
  <h1>{{DEVICE}}</h1>
  <div class="sub"><b>●</b> Connected over Wi-Fi</div>
</div>

<label class="btn" for="pick">
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5M5 12l7-7 7 7"/></svg>
  Send Photos &amp; Files
</label>
<input id="pick" type="file" multiple>

<h2 id="upH" style="display:none">Sending</h2>
<div class="card" id="up"></div>

<h2>From {{DEVICE}}</h2>
<div class="card" id="dl"><div class="empty">On the tablet, tap <b>Share → Send to iPhone</b>.<br>Files appear here instantly.</div></div>

<div class="toast" id="toast"></div>

<script>
const K=new URLSearchParams(location.search).get('k');
const q=s=>document.querySelector(s);
const fmt=b=>b<1024?b+' B':b<1048576?(b/1024).toFixed(0)+' KB':b<1073741824?(b/1048576).toFixed(1)+' MB':(b/1073741824).toFixed(2)+' GB';
const colors={pdf:'#ff3b30',doc:'#2f7cf6',docx:'#2f7cf6',xls:'#34c759',xlsx:'#34c759',csv:'#34c759',ppt:'#ff9500',pptx:'#ff9500',key:'#ff9500',
 zip:'#8e8e93',rar:'#8e8e93',jpg:'#af52de',jpeg:'#af52de',png:'#af52de',heic:'#af52de',gif:'#af52de',webp:'#af52de',
 mp4:'#5856d6',mov:'#5856d6',mkv:'#5856d6',mp3:'#ff2d55',m4a:'#ff2d55',wav:'#ff2d55',apk:'#3ddc84',txt:'#636366'};
function ext(n){const i=n.lastIndexOf('.');return i>0?n.slice(i+1).toLowerCase():''}
function icon(n){const e=ext(n);return '<div class="ico" style="background:'+(colors[e]||'#007aff')+'">'+(e||'FILE').slice(0,4).toUpperCase()+'</div>'}
function esc(s){return s.replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
function toast(t){const e=q('#toast');e.textContent=t;e.classList.add('on');clearTimeout(e._t);e._t=setTimeout(()=>e.classList.remove('on'),2200)}

q('#pick').addEventListener('change',async e=>{
  const files=[...e.target.files]; e.target.value='';
  if(!files.length)return;
  q('#upH').style.display='block';
  const rows=files.map(f=>{const r=document.createElement('div');r.className='row';
    r.innerHTML=icon(f.name)+'<div class="meta"><div class="name">'+esc(f.name)+'</div><div class="size">'+fmt(f.size)+'</div><div class="bar"><i></i></div></div><span class="st"></span>';
    q('#up').prepend(r);return r});
  for(let i=0;i<files.length;i++){await send(files[i],rows[i])}
  toast(files.length>1?files.length+' files sent':'Sent');
});
function send(f,row){return new Promise(res=>{
  const x=new XMLHttpRequest();
  x.open('POST','/upload?k='+K+'&name='+encodeURIComponent(f.name));
  x.setRequestHeader('Content-Type',f.type||'application/octet-stream');
  x.upload.onprogress=e=>{if(e.lengthComputable)row.querySelector('.bar i').style.width=(e.loaded/e.total*100)+'%'};
  x.onload=()=>{const ok=x.status==200;row.querySelector('.bar').style.display='none';
    row.querySelector('.st').innerHTML=ok?'<span class="done">✓ Sent</span>':'<span style="color:#ff3b30">Failed</span>';res()};
  x.onerror=()=>{row.querySelector('.st').innerHTML='<span style="color:#ff3b30">Failed</span>';res()};
  x.send(f);
})}

let seen='';
async function poll(){
  try{
    const r=await fetch('/files?k='+K,{cache:'no-store'});const j=await r.json();
    const key=j.items.map(i=>i.id).join(',');
    if(key!==seen){
      const fresh=seen!=='' && j.items.length && !seen.split(',').includes(String(j.items[0].id));
      seen=key;
      q('#dl').innerHTML=j.items.length?j.items.map(i=>i.text!==undefined
        ? '<div class="row">'+'<div class="ico" style="background:#636366">TXT</div><div class="meta"><div class="name">'+esc(i.text)+'</div><div class="size">Text</div></div>'+
          (/^https?:\/\//.test(i.text)?'<a class="pill" href="'+esc(i.text)+'">Open</a>':'<button class="pill" data-t="'+esc(i.text)+'">Copy</button>')+'</div>'
        : '<div class="row">'+icon(i.name)+'<div class="meta"><div class="name">'+esc(i.name)+'</div><div class="size">'+fmt(i.size)+'</div></div><a class="pill" href="/file?k='+K+'&id='+i.id+'" download>Get</a></div>'
      ).join(''):'<div class="empty">On the tablet, tap <b>Share → Send to iPhone</b>.<br>Files appear here instantly.</div>';
      if(fresh){toast('New from {{DEVICE}}');navigator.vibrate&&navigator.vibrate(30)}
    }
  }catch(e){}
  setTimeout(poll,1500);
}
document.addEventListener('click',e=>{const t=e.target.closest('[data-t]');if(t){navigator.clipboard.writeText(t.dataset.t);toast('Copied')}});
poll();
</script></body></html>"""
}
