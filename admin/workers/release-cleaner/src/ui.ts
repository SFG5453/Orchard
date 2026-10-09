export const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Release storage · Orchard</title><link rel="stylesheet" href="/ui.css"><script defer src="/ui.js"></script></head>
<body>
<header class="topbar"><div class="topbar-inner"><span class="brand">Orchard</span><span class="divider" aria-hidden="true"></span><h1>Release storage</h1><button id="load-manifests" type="button">Refresh</button></div></header>
<main>
<p class="notice">Published channels protect their manifests and bootstrapper objects. Keep old manifests for versions you still support, and pause publishing while deleting.</p>
<section aria-labelledby="channels-title"><div class="section-head"><div><h2 id="channels-title">Published channels</h2><p>Every channel in the bucket is included in the scan.</p></div><span id="channel-count" class="count"></span></div>
<div class="table-wrap"><table><thead><tr><th>Channel</th><th>Version</th><th>Platforms</th><th>Published</th></tr></thead><tbody id="channels"></tbody></table></div></section>
<section aria-labelledby="manifests-title"><div class="section-head"><div><h2 id="manifests-title">Release manifests</h2><p>Manifests used by a published channel cannot be retired.</p></div><span id="manifest-count" class="count"></span></div>
<div class="table-wrap"><table><thead><tr><th class="check-col"><span class="sr-only">Select</span></th><th>Manifest</th><th>Status</th><th>Size</th><th>Uploaded</th></tr></thead><tbody id="manifests"></tbody></table></div>
<div class="section-actions"><span id="manifest-summary">No selection</span><button id="retire" class="danger" type="button" disabled>Retire selected</button></div></section>
<section aria-labelledby="objects-title"><div class="section-head"><div><h2 id="objects-title">Unused objects</h2><p>Only objects unreferenced by retained manifests and published bootstrappers can appear here.</p></div><div class="button-group"><button id="scan" type="button">Scan objects</button><button id="clean-all-unused" class="danger" type="button">Clean all unused</button></div></div>
<p id="object-info" class="scan-info">Scan to find eligible objects.</p>
<div class="table-wrap"><table><thead><tr><th class="check-col"><span class="sr-only">Select</span></th><th>Object</th><th>Size</th><th>Uploaded</th></tr></thead><tbody id="objects"></tbody></table></div>
<div class="section-actions"><span id="object-summary">No selection</span><div class="button-group"><button id="next" type="button" disabled>Next page</button><button id="delete-objects" class="danger" type="button" disabled>Delete selected</button></div></div></section>
<p id="status" role="status" aria-live="polite"></p>
</main>
<dialog id="confirm-dialog" aria-labelledby="confirm-title"><h2 id="confirm-title">Confirm deletion</h2><p id="confirm-description"></p><label for="confirm-input">Type DELETE to continue</label><input id="confirm-input" type="text" autocomplete="off" spellcheck="false"><div class="dialog-actions"><button id="confirm-cancel" type="button">Cancel</button><button id="confirm-submit" class="danger" type="button" disabled>Delete</button></div></dialog>
</body></html>`;

export const style = `:root{color-scheme:dark;font:14px/1.5 Inter,system-ui,sans-serif;background:#10120e;color:#f0eee7}*{box-sizing:border-box}body{margin:0}button,input{font:inherit}button{padding:7px 12px;border:1px solid #4b564c;border-radius:6px;background:#252b25;color:#f0eee7;cursor:pointer;white-space:nowrap}button:hover:not(:disabled){background:#343e35}button:disabled{opacity:.45;cursor:not-allowed}button:focus-visible,input:focus-visible{outline:2px solid #a6d4bf;outline-offset:2px}.danger{border-color:#8e6159;background:#4a302c;color:#f4e5df}.danger:hover:not(:disabled){background:#633b35}.topbar{border-bottom:1px solid #30382f;background:#171b16}.topbar-inner{max-width:1200px;min-height:56px;margin:auto;padding:0 24px;display:flex;align-items:center;gap:16px}.topbar h1{font-size:16px;font-weight:600;margin:0;flex:1}.brand{font-weight:700}.divider{height:18px;border-left:1px solid #4b554b}main{max-width:1200px;margin:0 auto;padding:24px 24px 64px}.notice{margin:0 0 24px;padding:12px 16px;background:#20271f;border-left:3px solid #8cc5a5;color:#d3ded2}section{margin:0 0 28px;border:1px solid #353d34;border-radius:8px;background:#181d18;overflow:hidden}.section-head{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 20px;border-bottom:1px solid #353d34}.section-head h2{font-size:16px;line-height:1.3;margin:0;font-weight:650}.section-head p{margin:4px 0 0;color:#a4ada5}.count,.scan-info{color:#a4ada5}.table-wrap{overflow-x:auto}table{border-collapse:collapse;width:100%;text-align:left}th,td{padding:10px 16px;border-bottom:1px solid #30382f;vertical-align:middle}th{font-weight:600;color:#bec7bc;background:#1e241d;white-space:nowrap}tbody tr:hover{background:#20271f}tbody tr:last-child td{border-bottom:0}.check-col{width:42px;padding-right:0}td.check-col{padding-right:0}input[type=checkbox]{width:16px;height:16px;margin:0;accent-color:#8cc5a5;cursor:pointer}input[type=checkbox]:disabled{cursor:not-allowed}.key{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px;overflow-wrap:anywhere;min-width:310px}.muted{color:#a4ada5}.protected{color:#a6d4bf}.ready{color:#e4c28a}.empty{color:#a4ada5;padding:20px 16px}.section-actions{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:12px 20px;border-top:1px solid #353d34;color:#a4ada5}.button-group{display:flex;gap:8px}.scan-info{padding:12px 20px;margin:0;border-bottom:1px solid #353d34}#status{min-height:22px;margin:8px 0;color:#a6d4bf}.error{color:#e6a69d!important}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}dialog{width:min(420px,calc(100vw - 32px));padding:24px;border:1px solid #4b554b;border-radius:8px;background:#1d231d;color:#f0eee7;box-shadow:0 2px 8px #0004}dialog::backdrop{background:#0009}dialog h2{font-size:18px;margin:0 0 12px}dialog p{color:#c3cbc1;margin:0 0 20px}dialog label{display:block;margin-bottom:8px;font-weight:600}dialog input{width:100%;padding:9px 10px;border:1px solid #667365;border-radius:6px;background:#10120e;color:#f0eee7}.dialog-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:20px}@media(max-width:640px){.topbar-inner{padding:0 16px}main{padding:16px 12px 48px}.section-head{align-items:flex-start;padding:14px 16px;flex-wrap:wrap}.section-head p{max-width:260px}.section-actions{padding:12px 16px;flex-wrap:wrap}.key{min-width:230px}th,td{padding:10px 12px}}`;

export const script = `const $=id=>document.getElementById(id);
let manifestRows=[],objectRows=[],nextCursor=null,busy=false;
const format=n=>n<1024?n+' B':n<1048576?(n/1024).toFixed(1)+' KiB':n<1073741824?(n/1048576).toFixed(1)+' MiB':(n/1073741824).toFixed(2)+' GiB';
const date=value=>new Date(value).toLocaleString();
function status(message,error=false){$('status').textContent=message;$('status').className=error?'error':''}
async function api(path,options){const response=await fetch(path,options);const data=await response.json();if(!response.ok)throw Error(data.error||'Request failed');return data}
function cell(tr,value,className){const td=document.createElement('td');td.textContent=value;if(className)td.className=className;tr.append(td);return td}
function empty(tbody,message,span){const tr=document.createElement('tr');const td=cell(tr,message,'empty');td.colSpan=span;tbody.append(tr)}
function selected(container,rows){return [...$(container).querySelectorAll('input:checked')].map(input=>rows[Number(input.value)])}
function row(container,entry,index,locked,statusText){const tr=document.createElement('tr');const box=cell(tr,'','check-col');const input=document.createElement('input');input.type='checkbox';input.value=String(index);input.disabled=locked;input.setAttribute('aria-label','Select '+entry.key);box.append(input);cell(tr,entry.key,'key');if(container==='manifests')cell(tr,statusText,entry.active?'protected':entry.eligible?'ready':'muted');cell(tr,format(entry.size));cell(tr,date(entry.uploaded),'muted');$(container).append(tr)}
function updateButtons(){const manifests=selected('manifests',manifestRows),objects=selected('objects',objectRows);$('manifest-summary').textContent=manifests.length?manifests.length+' selected':'No selection';$('object-summary').textContent=objects.length?objects.length+' selected · '+format(objects.reduce((n,o)=>n+o.size,0)):'No selection';$('retire').disabled=busy||!manifests.length||manifests.length>100;$('delete-objects').disabled=busy||!objects.length||objects.length>100;$('next').disabled=busy||!nextCursor}
function loading(value){busy=value;for(const id of ['load-manifests','scan','clean-all-unused'])$(id).disabled=value;updateButtons()}
function resetObjects(){objectRows=[];nextCursor=null;$('objects').replaceChildren();empty($('objects'),'Scan to find unused objects.',4);$('object-info').textContent='Scan to find eligible objects.';updateButtons()}
async function loadManifests(){loading(true);status('Reading release metadata…');try{const data=await api('/api/manifests');const channels=$('channels');channels.replaceChildren();data.channels.sort((a,b)=>a.name.localeCompare(b.name)).forEach(entry=>{const tr=document.createElement('tr');cell(tr,entry.name);cell(tr,entry.version);cell(tr,entry.platforms.join(', '));cell(tr,date(entry.uploaded),'muted');channels.append(tr)});if(!data.channels.length)empty(channels,'No published channels found.',4);$('channel-count').textContent=data.channels.length+' published';manifestRows=data.manifests.sort((a,b)=>a.key.localeCompare(b.key));const manifests=$('manifests');manifests.replaceChildren();manifestRows.forEach((entry,i)=>row('manifests',entry,i,!entry.eligible,entry.active?'Protected by '+entry.activeChannels.join(', '):entry.eligible?'Eligible':'Too new'));if(!manifestRows.length)empty(manifests,'No manifests found.',5);$('manifest-count').textContent=manifestRows.length+' total · minimum age '+data.minAgeDays+' days';resetObjects();status('Release metadata is current.');}catch(e){status(e.message,true)}finally{loading(false)}}
async function scan(cursor){loading(true);status('Scanning references and objects…');try{const path='/api/orphans'+(cursor?'?cursor='+encodeURIComponent(cursor):'');const data=await api(path);objectRows=data.candidates;const objects=$('objects');objects.replaceChildren();objectRows.forEach((entry,i)=>row('objects',entry,i,false,''));if(!objectRows.length)empty(objects,'No eligible objects on this page.',4);nextCursor=data.cursor;$('object-info').textContent=data.scanned+' scanned on this page · '+objectRows.length+' eligible · minimum age '+data.minAgeDays+' days';status(nextCursor?'Page scanned. Continue to inspect more objects.':'Scan complete.');}catch(e){status(e.message,true)}finally{loading(false)}}
function confirmDelete(description){return new Promise(resolve=>{const dialog=$('confirm-dialog');const input=$('confirm-input');$('confirm-description').textContent=description;input.value='';$('confirm-submit').disabled=true;dialog.showModal();input.focus();dialog.addEventListener('close',()=>resolve(dialog.returnValue==='confirm'),{once:true})})}
async function remove(kind){const rows=selected(kind==='manifest'?'manifests':'objects',kind==='manifest'?manifestRows:objectRows);if(!rows.length)return;const description=kind==='manifest'?rows.length+' manifest(s) will be retired.':'Delete '+rows.length+' unused object(s), '+format(rows.reduce((n,o)=>n+o.size,0))+' total?';if(!await confirmDelete(description))return;loading(true);status('Rechecking references before deletion…');try{const path=kind==='manifest'?'/api/retire':'/api/delete-objects';const field=kind==='manifest'?'manifests':'objects';const result=await api(path,{method:'POST',headers:{'Content-Type':'application/json','X-Orchard-Admin':'1'},body:JSON.stringify({confirm:'DELETE',[field]:rows.map(({key,version})=>({key,version}))})});if(kind==='manifest')await loadManifests();else await scan(null);status('Deleted '+result.deleted.length+' '+(kind==='manifest'?'manifest(s).':'object(s).'));}catch(e){status(e.message,true)}finally{loading(false)}}
async function cleanAllUnused(){
  if(busy)return;
  loading(true);
  let deleted=0,bytes=0,pages=0;
  try{
    if(!await confirmDelete('Delete all eligible unused objects across every page? Retained manifests and published bootstrappers stay protected. The minimum object age still applies. Pause publishing until cleanup finishes.'))return;
    resetObjects();
    $('object-info').textContent='Cleaning all pages…';
    let cursor=null;
    do{
      status('Scanning page '+(pages+1)+'… '+deleted+' object(s) deleted.');
      const page=await api('/api/orphans'+(cursor?'?cursor='+encodeURIComponent(cursor):''));
      pages++;
      // Each batch reuses the server's reference, age, and version checks.
      for(let offset=0;offset<page.candidates.length;offset+=100){
        const batch=page.candidates.slice(offset,offset+100);
        const result=await api('/api/delete-objects',{
          method:'POST',headers:{'Content-Type':'application/json','X-Orchard-Admin':'1'},
          body:JSON.stringify({confirm:'DELETE',objects:batch.map(({key,version})=>({key,version}))})
        });
        const keys=new Set(result.deleted);
        deleted+=keys.size;
        bytes+=batch.reduce((sum,entry)=>sum+(keys.has(entry.key)?entry.size:0),0);
        status('Cleaning page '+pages+'… '+deleted+' object(s) deleted · '+format(bytes)+' freed.');
      }
      // Empty candidate pages can still lead to eligible objects on later pages.
      cursor=page.cursor;
    }while(cursor);
    $('object-info').textContent=pages+' page(s) scanned · '+deleted+' object(s) deleted · '+format(bytes)+' freed';
    status('Cleanup complete. Deleted '+deleted+' unused object(s), '+format(bytes)+' total.');
  }catch(e){
    $('object-info').textContent='Cleanup stopped · '+deleted+' object(s) deleted · '+format(bytes)+' freed';
    status('Cleanup stopped after deleting '+deleted+' object(s). '+e.message,true);
  }finally{loading(false)}
}
$('confirm-input').addEventListener('input',event=>{$('confirm-submit').disabled=event.target.value!=='DELETE'});$('confirm-cancel').addEventListener('click',()=>$('confirm-dialog').close('cancel'));$('confirm-submit').addEventListener('click',()=>$('confirm-dialog').close('confirm'));
$('manifests').addEventListener('change',updateButtons);$('objects').addEventListener('change',updateButtons);$('load-manifests').addEventListener('click',loadManifests);$('scan').addEventListener('click',()=>scan(null));$('next').addEventListener('click',()=>scan(nextCursor));$('retire').addEventListener('click',()=>remove('manifest'));$('delete-objects').addEventListener('click',()=>remove('object'));$('clean-all-unused').addEventListener('click',cleanAllUnused);resetObjects();loadManifests();`;
