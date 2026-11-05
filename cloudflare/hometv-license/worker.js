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
const sessions=x=>x.sessions|| (x.device&&x.tokenHash?{[x.device]:x.tokenHash}:{});
const deviceLimit=x=>x.maxDevices==="unlimited"?"unlimited":Math.max(1,Number(x.maxDevices)||1);
// Expiry dates use UAE calendar time. The exclusive boundary is midnight
// after the selected date, preserving validity through 23:59:59.999 Asia/Dubai.
const expiryExclusive=x=>{
 if(x.expiry==="unlimited")return Date.parse("2101-01-01T00:00:00+04:00");
 const value=String(x.expiry||"");
 if(!/^\\d{4}-\\d{2}-\\d{2}$/.test(value))return NaN;
 const calendarCheck=Date.parse(value+"T00:00:00Z");
 if(!Number.isFinite(calendarCheck)||new Date(calendarCheck).toISOString().slice(0,10)!==value)return NaN;
 return Date.parse(value+"T00:00:00+04:00")+24*60*60*1000;
};
const displayExpiry=x=>x.expiry==="unlimited"?"2100-12-31":x.expiry;
const expired=x=>!Number.isFinite(expiryExclusive(x)) || Date.now()>=expiryExclusive(x);
const customerFields=b=>Object.fromEntries(["issuedTo","mobile","email","location","remarks"].map(k=>[k,String(b[k]||"").trim().slice(0,k==="remarks"?500:150)]));

const base64url=bytes=>btoa(String.fromCharCode(...new Uint8Array(bytes)))
 .split("+").join("-").split("/").join("_").split("=").join("");
async function issueSignedLease(env,row,device,tokenHash){
 if(!env.LICENSE_SIGNING_PRIVATE_JWK)throw Error("Licence signing is not configured");
 const privateJwk=JSON.parse(env.LICENSE_SIGNING_PRIVATE_JWK);
 const key=await crypto.subtle.importKey(
  "jwk",privateJwk,{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]
 );
 const now=Date.now();
 const expiresAt=expiryExclusive(row);
 if(!Number.isFinite(expiresAt)||expiresAt<=now)throw Error("Invalid or expired licence");
 const payload={
  version:1,
  licenseId:String(row.id||row.passwordHash.slice(0,16)),
  device,
  tokenHash,
  issuedAt:now,
  serverTime:now,
  expiresAt
 };
 const lease=base64url(enc(JSON.stringify(payload)));
 const signature=await crypto.subtle.sign(
  "RSASSA-PKCS1-v1_5",key,enc(lease)
 );
 return {lease,signature:base64url(signature),serverTime:now};
}

async function serve(req,env){
 if(!env.GITHUB_TOKEN||!env.GITHUB_REPO)return json({error:"Worker not configured"},503);
 const u=new URL(req.url),path=u.pathname;
 if(path==="/admin"&&req.method==="GET")return new Response("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>HomeTV Admin</title><style>body{font:16px Arial;background:#101e31;color:white;max-width:1400px;margin:auto;padding:20px}section{background:#233b58;padding:18px;margin:16px 0;border-radius:10px}input,select,button{padding:10px;margin:5px}button:focus,input:focus,select:focus{outline:3px solid #67b8ff}button{background:#ffb900;border:0;border-radius:5px;cursor:pointer}td,th{padding:10px;border-bottom:1px solid #607087;text-align:left}table{width:100%;white-space:nowrap}#result{overflow-wrap:anywhere}</style></head><body><h1>HomeTV License Manager</h1><section><input type=\"password\" id=\"secret\" placeholder=\"Administrator token\" size=\"40\"><button onclick=\"load()\">Sign in / Refresh</button></section><div id=\"private\" hidden><section><h2>Generate License Key</h2><select id=\"expiry\" onchange=\"el('date').hidden=this.value!=='date';if(this.value!=='date')el('date').value=''\"><option value=\"unlimited\">Unlimited</option><option value=\"date\">Expiry date</option></select><input type=\"date\" id=\"date\" hidden><label>Device limit <select id=\"limit\"><option value=\"1\">1 device</option><option value=\"2\">2 devices</option><option value=\"3\">3 devices</option><option value=\"5\">5 devices</option><option value=\"unlimited\">Unlimited devices</option></select></label><label>Issued To * <input id=\"issuedTo\" required placeholder=\"Customer name\"></label><label>Mobile <input id=\"mobile\" placeholder=\"Optional\"></label><label>Email <input id=\"email\" placeholder=\"Optional\"></label><label>Location <input id=\"location\" placeholder=\"Optional\"></label><label>Remarks <input id=\"remarks\" placeholder=\"Optional\"></label><button onclick=\"create()\">Generate License Key</button><p id=\"result\"></p></section><section><h2>Licenses</h2><label>Status <select id=\"statusFilter\" onchange=\"render()\"><option>All</option><option>Active</option><option>Available</option><option>Expired</option><option>Revoked</option></select></label><label>Search <input id=\"search\" placeholder=\"Name, key, contact\" oninput=\"render()\"></label><span id=\"count\"></span><div style=\"overflow-x:auto\"><table><thead><tr><th>License Key</th><th>Issued To</th><th>Contact</th><th>Issued</th><th>Expiry</th><th>Device limit</th><th>Used</th><th>Status</th><th>Actions</th></tr></thead><tbody id=\"records\"></tbody></table></div></section></div><p id=\"message\"></p><script>const el=x=>document.getElementById(x);let licenses=[];\n async function request(path,body={}){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+el('secret').value},body:JSON.stringify(body),cache:'no-store'});const d=await r.json();if(!r.ok)throw Error(d.error||'Request failed');return d}\n function td(tr,value){const c=document.createElement('td');c.textContent=value??'';tr.append(c);return c}\n function btn(parent,label,fn){const b=document.createElement('button');b.textContent=label;b.onclick=fn;parent.append(b)}\n async function copy(key){try{await navigator.clipboard.writeText(key)}catch(e){const t=document.createElement('textarea');t.value=key;document.body.append(t);t.select();document.execCommand('copy');t.remove()}el('message').textContent='License key copied'}\n function render(){const filter=el('statusFilter').value,q=el('search').value.trim().toLowerCase();const items=licenses.filter(x=>(filter==='All'||x.status===filter)&&[x.key,x.issuedTo,x.mobile,x.email,x.location,x.remarks].some(v=>String(v||'').toLowerCase().includes(q)));el('records').replaceChildren();for(const x of items){const tr=document.createElement('tr');const key=td(tr,x.key||('Legacy: '+x.id));if(x.key)btn(key,'Copy',()=>copy(x.key));td(tr,x.issuedTo||'—');td(tr,[x.mobile,x.email,x.location].filter(Boolean).join(' / '));td(tr,x.issuedAt?x.issuedAt.slice(0,10):'—');for(const v of [x.expiry,x.maxDevices,x.used,x.status])td(tr,v);const actions=td(tr,'');btn(actions,'Edit',()=>edit(x));btn(actions,'Delete',()=>removeLicense(x));if(!x.revoked){btn(actions,'Release',()=>change(x.id,'release'));btn(actions,'Revoke',()=>change(x.id,'revoke'))}el('records').append(tr)}el('count').textContent=items.length+' / '+licenses.length}\n async function load(){try{const d=await request('/admin/list');licenses=d.licenses;el('private').hidden=false;render();el('message').textContent=licenses.length+' licenses loaded'}catch(e){el('private').hidden=true;el('message').textContent=e.message}}\n async function create(){try{const issuedTo=el('issuedTo').value.trim();if(!issuedTo)throw Error('Issued To is mandatory');const expiry=el('expiry').value==='unlimited'?'2100-12-31':el('date').value;if(!expiry)throw Error('Choose an expiry date');const details={issuedTo,mobile:el('mobile').value.trim(),email:el('email').value.trim(),location:el('location').value.trim(),remarks:el('remarks').value.trim()};const d=await request('/admin/create',{...details,expiry,maxDevices:el('limit').value});el('result').replaceChildren();const label=document.createElement('strong');label.textContent='NEW LICENSE KEY: '+d.password;el('result').append(label);btn(el('result'),'Copy Key',()=>copy(d.password));for(const id of ['issuedTo','mobile','email','location','remarks'])el(id).value='';await load()}catch(e){el('message').textContent=e.message}}\n async function edit(x){const issuedTo=prompt('Issued To (required)',x.issuedTo||'');if(issuedTo===null)return;if(!issuedTo.trim()){alert('Issued To is mandatory');return}const mobile=prompt('Mobile (optional)',x.mobile||'');if(mobile===null)return;const email=prompt('Email (optional)',x.email||'');if(email===null)return;const location=prompt('Location (optional)',x.location||'');if(location===null)return;const remarks=prompt('Remarks (optional)',x.remarks||'');if(remarks===null)return;try{await request('/admin/update',{id:x.id,issuedTo:issuedTo.trim(),mobile,email,location,remarks});await load()}catch(e){el('message').textContent=e.message}}\n async function removeLicense(x){if(!confirm('Permanently DELETE license '+(x.key||x.id)+'? This cannot be undone and will stop activation.'))return;const typed=prompt('To confirm permanent deletion, type DELETE');if(typed!=='DELETE')return;try{await request('/admin/delete',{id:x.id});await load();el('message').textContent='License permanently deleted'}catch(e){el('message').textContent=e.message}}\n async function change(id,action){if(!confirm(action+' '+id+'?'))return;try{await request('/admin/'+action,{id});await load()}catch(e){el('message').textContent=e.message}}</script></body></html>",{headers:{"content-type":"text/html; charset=utf-8","cache-control":"no-store","content-security-policy":"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'","x-content-type-options":"nosniff"}});
 if(path==="/download"&&req.method==="GET"){
  if(!env.APK_DOWNLOAD_URL)return new Response("<!doctype html><title>HomeTV Download</title><body style='font:18px Arial;background:#101e31;color:white;padding:32px'><h1>HomeTV for Android TV</h1><p>The APK download is being prepared. Please check back later.</p></body>",{status:503,headers:{"content-type":"text/html; charset=utf-8","cache-control":"no-store"}});
  const target=String(env.APK_DOWNLOAD_URL);
  if(!/^https:\/\//.test(target))return json({error:"Download URL not configured"},503);
  return Response.redirect(target,302);
 }
 if(path==="/health")return json({ok:true});
 if(req.method!=="POST")return json({error:"Use POST"},405);
 let body;try{body=await req.json()}catch{return json({error:"Invalid JSON"},400)}
 if(path==="/admin/list"){
  if(!env.ADMIN_TOKEN||req.headers.get("Authorization")!=="Bearer "+env.ADMIN_TOKEN)return json({error:"Unauthorized"},401);
  const {records}=await readLicenses(env);
  return json({licenses:records.map(x=>({id:x.id||x.passwordHash.slice(0,12),key:x.licenseKey||null,expiry:displayExpiry(x),maxDevices:deviceLimit(x),used:Object.keys(sessions(x)).length,status:x.revoked?"Revoked":expired(x)?"Expired":Object.keys(sessions(x)).length?"Active":"Available",revoked:!!x.revoked,issuedTo:x.issuedTo||"",mobile:x.mobile||"",email:x.email||"",location:x.location||"",remarks:x.remarks||"",issuedAt:x.issuedAt||""}))});
 }
 if(path==="/admin/update"){
  if(!env.ADMIN_TOKEN||req.headers.get("Authorization")!=="Bearer "+env.ADMIN_TOKEN)return json({error:"Unauthorized"},401);
  const customer=customerFields(body);
  if(!customer.issuedTo)return json({error:"Issued To is mandatory"},400);
  const {records,sha}=await readLicenses(env);
  const row=records.find(x=>(x.id||x.passwordHash.slice(0,12))===body.id);
  if(!row)return json({error:"Not found"},404);
  Object.assign(row,customer);
  await put(env,licensePath(env),JSON.stringify(records,null,2)+"\n",sha);
  return json({ok:true});
 }
 if(path==="/admin/delete"){
  if(!env.ADMIN_TOKEN||req.headers.get("Authorization")!=="Bearer "+env.ADMIN_TOKEN)return json({error:"Unauthorized"},401);
  const {records,sha}=await readLicenses(env);
  const i=records.findIndex(x=>(x.id||x.passwordHash.slice(0,12))===body.id);
  if(i<0)return json({error:"Not found"},404);
  records.splice(i,1);
  await put(env,licensePath(env),JSON.stringify(records,null,2)+"\n",sha);
  return json({ok:true});
 }
 if(path==="/admin/revoke"){
  if(!env.ADMIN_TOKEN||req.headers.get("Authorization")!=="Bearer "+env.ADMIN_TOKEN)return json({error:"Unauthorized"},401);
  const {records,sha}=await readLicenses(env);
  const row=records.find(x=>(x.id||x.passwordHash.slice(0,12))===body.id);
  if(!row)return json({error:"Not found"},404);
  row.revoked=true;row.device=null;row.tokenHash=null;row.sessions={};
  await put(env,licensePath(env),JSON.stringify(records,null,2)+"\n",sha);
  return json({ok:true});
 }
 if(path==="/admin/create"){
  if(!env.ADMIN_TOKEN||req.headers.get("Authorization")!=="Bearer "+env.ADMIN_TOKEN)return json({error:"Unauthorized"},401);
  const expiry=String(body.expiry||"unlimited");
  if(expiry!=="unlimited" && (!/^\d{4}-\d{2}-\d{2}$/.test(expiry)||!Number.isFinite(Date.parse(expiry+"T23:59:59Z"))))return json({error:"Invalid expiry"},400);
  const maxDevices=body.maxDevices==="unlimited"?"unlimited":Number(body.maxDevices??1);
  if(maxDevices!=="unlimited"&&(!Number.isInteger(maxDevices)||maxDevices<1||maxDevices>100))return json({error:"Invalid device limit"},400);
  const customer=customerFields(body);
  if(!customer.issuedTo)return json({error:"Issued To is mandatory"},400);
  const {records,sha}=await readLicenses(env);
  const alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes=crypto.getRandomValues(new Uint8Array(20));
  const chars=Array.from(bytes,b=>alphabet[b%alphabet.length]).join("");
  const password="HTV-"+chars.match(/.{1,5}/g).join("-");
  const passwordHash=await hash(password);
  records.unshift({licenseKey:password,passwordHash,expiry,maxDevices,...customer,issuedAt:new Date().toISOString(),sessions:{},device:null,tokenHash:null});
  await put(env,licensePath(env),JSON.stringify(records,null,2)+"\n",sha);
  return json({ok:true,password,expiry,maxDevices});
 }
 if(path==="/admin/release"){
  if(!env.ADMIN_TOKEN||req.headers.get("Authorization")!=="Bearer "+env.ADMIN_TOKEN)return json({error:"Unauthorized"},401);
  const {records,sha}=await readLicenses(env);
  const row=records.find(x=>(x.id||x.passwordHash.slice(0,12))===body.id);
  if(!row)return json({error:"Not found"},404);
  row.device=null;row.tokenHash=null;row.sessions={};
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
  const active=sessions(row);
  if(!active[device]&&deviceLimit(row)!=="unlimited"&&Object.keys(active).length>=deviceLimit(row))return json({error:"Device limit reached. Surrender a device or request admin release."},409);
  const token=crypto.randomUUID()+"-"+crypto.randomUUID();
  active[device]=await hash(token);row.sessions=active;row.device=null;row.tokenHash=null;
  await put(env,licensePath(env),JSON.stringify(records,null,2)+"\n",sha);
  return json({ok:true,token,expiry:row.expiry});
 }
 const token=String(body.token||"");
 if(token.length<20||token.length>200)return json({error:"Invalid token"},401);
 const tokenHash=await hash(token);
 row=records.find(x=>sessions(x)[device]===tokenHash);
 if(!row)return json({error:"License not active"},401);
 if(path==="/surrender"){
  const active=sessions(row);delete active[device];row.sessions=active;row.device=null;row.tokenHash=null;
  await put(env,licensePath(env),JSON.stringify(records,null,2)+"\n",sha);
  return json({ok:true});
 }
 if(expired(row))return json({error:"License expired"},403);
 if(path==="/validate"||path==="/validate-lease"){
  const signed=await issueSignedLease(env,row,device,tokenHash);
  return json({ok:true,expiry:displayExpiry(row),...signed});
 }
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