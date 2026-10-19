// HomeTV licensing Cloudflare Worker.
// Secrets: GITHUB_TOKEN (fine-grained Contents read/write), ADMIN_TOKEN.
// Vars: GITHUB_REPO (private repo owner/name), LICENSE_PATH (optional, defaults licenses.json),
// PLAYLIST_URL (secret, full upstream M3U URL).
// Never place GITHUB_TOKEN or ADMIN_TOKEN in the Android app.
const json=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:{"content-type":"application/json","cache-control":"no-store"}});
const enc=s=>new TextEncoder().encode(s);
const hex=a=>[...new Uint8Array(a)].map(x=>x.toString(16).padStart(2,"0")).join("");
const hash=async s=>hex(await crypto.subtle.digest("SHA-256",enc(s)));
const b64decode=s=>atob(s.replace(/\s/g,""));
const b64encode=s=>btoa(String.fromCharCode(...enc(s)));
const api=(env,path)=>"https://api.github.com/repos/"+env.GITHUB_REPO+"/contents/"+path.split("/").map(encodeURIComponent).join("/");
async function gh(env,path){
 const r=await fetch(api(env,path),{headers:{Authorization:"Bearer "+env.GITHUB_TOKEN,Accept:"application/vnd.github+json","User-Agent":"HomeTV-License-Worker"},cf:{cacheTtl:0}});
 if(r.status===404)return {sha:null,body:null};
 if(!r.ok)throw Error("GitHub read failed: "+r.status);
 const d=await r.json();
 return {sha:d.sha,body:decodeURIComponent(escape(b64decode(d.content)))};
}
async function put(env,path,body,sha){
 const r=await fetch(api(env,path),{method:"PUT",headers:{Authorization:"Bearer "+env.GITHUB_TOKEN,Accept:"application/vnd.github+json","Content-Type":"application/json","User-Agent":"HomeTV-License-Worker"},body:JSON.stringify({message:"Update HomeTV license state",content:b64encode(body),...(sha?{sha}:{})})});
 if(!r.ok)throw Error("GitHub write failed: "+r.status);
}
const licensePath=env=>env.LICENSE_PATH||"licenses.json";
async function readLicenses(env){
 const file=await gh(env,licensePath(env));
 if(!file.body)throw Error("License file missing");
 const records=JSON.parse(file.body);
 if(!Array.isArray(records))throw Error("License file must be a JSON array");
 return {records,sha:file.sha};
}
const expired=x=>x.expiry!=="unlimited" && (!/^\d{4}-\d{2}-\d{2}$/.test(x.expiry||"") || Date.now()>Date.parse(x.expiry+"T23:59:59Z"));
async function serve(req,env){
 if(!env.GITHUB_TOKEN||!env.GITHUB_REPO)return json({error:"Worker not configured"},503);
 const u=new URL(req.url),path=u.pathname;
 if(path==="/admin"&&req.method==="GET")return new Response("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>HomeTV Admin</title><style>body{font:16px Arial;background:#101e31;color:white;max-width:960px;margin:auto;padding:20px}section{background:#233b58;padding:18px;margin:16px 0;border-radius:10px}input,select,button{padding:10px;margin:5px}button{background:#ffb900;border:0;border-radius:5px;cursor:pointer}td,th{padding:10px;border-bottom:1px solid #607087;text-align:left}table{width:100%}#result{overflow-wrap:anywhere}</style></head><body><h1>HomeTV License Manager</h1><section><input type=\"password\" id=\"secret\" placeholder=\"Administrator token\" size=\"40\"><button onclick=\"load()\">Sign in / Refresh</button></section><div id=\"private\" hidden><section><h2>Generate License Key</h2><select id=\"expiry\"><option value=\"unlimited\">Unlimited</option><option value=\"date\">Expiry date</option></select><input type=\"date\" id=\"date\"><button onclick=\"create()\">Generate License Key</button><p id=\"result\"></p></section><section><h2>Licenses</h2><div style=\"overflow-x:auto\"><table><thead><tr><th>Reference</th><th>Expiry</th><th>Status</th><th>Actions</th></tr></thead><tbody id=\"records\"></tbody></table></div></section></div><p id=\"message\"></p><script>\nconst el=x=>document.getElementById(x);\nasync function request(path,body={}){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+el('secret').value},body:JSON.stringify(body),cache:'no-store'});const d=await r.json();if(!r.ok)throw Error(d.error||'Request failed');return d}\nasync function load(){try{const d=await request('/admin/list');el('private').hidden=false;el('records').replaceChildren();for(const x of d.licenses){const tr=document.createElement('tr');for(const v of [x.id,x.expiry,x.status]){const td=document.createElement('td');td.textContent=v;tr.append(td)}const td=document.createElement('td');for(const action of ['release','revoke']){const b=document.createElement('button');b.textContent=action;b.disabled=x.revoked;b.onclick=()=>change(x.id,action);td.append(b)}tr.append(td);el('records').append(tr)}el('message').textContent=d.licenses.length+' licenses loaded'}catch(e){el('private').hidden=true;el('message').textContent=e.message}}\nasync function create(){try{const expiry=el('expiry').value==='unlimited'?'unlimited':el('date').value;if(!expiry)throw Error('Choose an expiry date');const d=await request('/admin/create',{expiry});el('result').textContent='NEW LICENSE KEY — SAVE NOW: '+d.password;await load()}catch(e){el('message').textContent=e.message}}\nasync function change(id,action){if(!confirm(action+' '+id+'?'))return;try{await request('/admin/'+action,{id});await load()}catch(e){el('message').textContent=e.message}}\n</script></body></html>",{headers:{"content-type":"text/html; charset=utf-8","cache-control":"no-store","content-security-policy":"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'","x-content-type-options":"nosniff"}});
 if(path==="/health")return json({ok:true});
 if(req.method!=="POST")return json({error:"Use POST"},405);
 let body;try{body=await req.json()}catch{return json({error:"Invalid JSON"},400)}
 if(path==="/admin/list"){
  if(!env.ADMIN_TOKEN||req.headers.get("Authorization")!=="Bearer "+env.ADMIN_TOKEN)return json({error:"Unauthorized"},401);
  const {records}=await readLicenses(env);
  return json({licenses:records.map(x=>({id:x.id||x.passwordHash.slice(0,12),expiry:x.expiry,status:x.revoked?"Revoked":expired(x)?"Expired":x.device?"Active":"Available",revoked:!!x.revoked}))});
 }
 if(path==="/admin/revoke"){
  if(!env.ADMIN_TOKEN||req.headers.get("Authorization")!=="Bearer "+env.ADMIN_TOKEN)return json({error:"Unauthorized"},401);
  const {records,sha}=await readLicenses(env);
  const row=records.find(x=>(x.id||x.passwordHash.slice(0,12))===body.id);
  if(!row)return json({error:"Not found"},404);
  row.revoked=true;row.device=null;row.tokenHash=null;
  await put(env,licensePath(env),JSON.stringify(records,null,2)+"\n",sha);
  return json({ok:true});
 }
 if(path==="/admin/create"){
  if(!env.ADMIN_TOKEN||req.headers.get("Authorization")!=="Bearer "+env.ADMIN_TOKEN)return json({error:"Unauthorized"},401);
  const expiry=String(body.expiry||"unlimited");
  if(expiry!=="unlimited" && (!/^\d{4}-\d{2}-\d{2}$/.test(expiry)||!Number.isFinite(Date.parse(expiry+"T23:59:59Z"))))return json({error:"Invalid expiry"},400);
  const {records,sha}=await readLicenses(env);
  const bytes=crypto.getRandomValues(new Uint8Array(24));
  const password=Array.from(bytes,b=>b.toString(16).padStart(2,"0")).join("");
  const passwordHash=await hash(password);
  records.push({passwordHash,expiry,device:null,tokenHash:null});
  await put(env,licensePath(env),JSON.stringify(records,null,2)+"\n",sha);
  return json({ok:true,password,expiry});
 }
 if(path==="/admin/release"){
  if(!env.ADMIN_TOKEN||req.headers.get("Authorization")!=="Bearer "+env.ADMIN_TOKEN)return json({error:"Unauthorized"},401);
  const {records,sha}=await readLicenses(env);
  const row=records.find(x=>(x.id||x.passwordHash.slice(0,12))===body.id);
  if(!row)return json({error:"Not found"},404);
  row.device=null;row.tokenHash=null;
  await put(env,licensePath(env),JSON.stringify(records,null,2)+"\n",sha);
  return json({ok:true});
 }
 const device=String(body.device||"").trim();
 if(!/^[a-zA-Z0-9_-]{16,128}$/.test(device))return json({error:"Invalid device identifier"},400);
 const {records,sha}=await readLicenses(env);
 let row;
 if(path==="/activate"){
  const password=String(body.password||"");
  if(password.length<8||password.length>128)return json({error:"Invalid password"},400);
  const passwordHash=await hash(password);
  row=records.find(x=>x.passwordHash===passwordHash);
  if(!row)return json({error:"Invalid password"},401);
  if(row.revoked)return json({error:"License revoked"},403);
  if(expired(row))return json({error:"License expired"},403);
  if(row.device&&row.device!==device)return json({error:"License already assigned to another installation. Surrender or request admin release."},409);
  const token=crypto.randomUUID()+"-"+crypto.randomUUID();
  row.device=device;row.tokenHash=await hash(token);
  await put(env,licensePath(env),JSON.stringify(records,null,2)+"\n",sha);
  return json({ok:true,token,expiry:row.expiry});
 }
 const token=String(body.token||"");
 if(token.length<20||token.length>200)return json({error:"Invalid token"},401);
 const tokenHash=await hash(token);
 row=records.find(x=>x.device===device&&x.tokenHash===tokenHash);
 if(!row)return json({error:"License not active"},401);
 if(path==="/surrender"){
  row.device=null;row.tokenHash=null;
  await put(env,licensePath(env),JSON.stringify(records,null,2)+"\n",sha);
  return json({ok:true});
 }
 if(expired(row))return json({error:"License expired"},403);
 if(path==="/validate")return json({ok:true,expiry:row.expiry});
 if(path==="/playlist"){
  // The upstream playlist URL (including its token) must be a Worker secret,
  // never published in the Android APK or in this repository.
  if(!env.PLAYLIST_URL)return json({error:"Playlist source not configured"},503);
  const upstream=await fetch(env.PLAYLIST_URL,{headers:{"User-Agent":"HomeTV-License-Worker","Cache-Control":"no-store"},cf:{cacheTtl:0},redirect:"follow"});
  if(!upstream.ok)return json({error:"Playlist source unavailable"},502);
  const body=await upstream.text();
  if(!body.trimStart().startsWith("#EXTM3U"))return json({error:"Invalid playlist response"},502);
  return new Response(body,{headers:{"content-type":"application/vnd.apple.mpegurl; charset=utf-8","cache-control":"no-store"}});
 }
 return json({error:"Not found"},404);
}
export default {async fetch(req,env){
 try{return await serve(req,env)}catch(e){console.error(e);return json({error:"Licensing service unavailable"},503)}
}};
