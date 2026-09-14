const $=s=>document.querySelector(s),$$=s=>[...document.querySelectorAll(s)];
let me=null,csrf='';
let contractPage=1,auditPage=1;
let contractSchema=null,upgradeSchema=null,contractDetail=null;

const labels={DRAFT:['填报中','draft'],FINALIZED:['已定稿','finalized'],SIGNED:['已签署','signed'],VOID:['已作废','void']};
const JOB_TEXT={QUEUED:'排队中',RUNNING:'生成中',SUCCEEDED:'已完成',FAILED:'失败'};

function cookieValue(name){return document.cookie.split('; ').find(v=>v.startsWith(name+'='))?.slice(name.length+1)||''}

async function api(path,opt={}){
  const headers=new Headers(opt.headers||{});
  if(opt.body && !(opt.body instanceof FormData))headers.set('Content-Type','application/json');
  if(!['GET','HEAD'].includes(opt.method||'GET')){
    // 登录与改密会轮换这个 Cookie，每次写操作都重新读取，避免多标签页失效。
    csrf=cookieValue('QH_CSRF');
    if(!csrf){const r=await fetch('/api/v1/auth/csrf',{credentials:'same-origin'});if(!r.ok)throw Error('无法验证请求，请稍后重试');csrf=(await r.json()).csrf_token}
    headers.set('X-CSRF-Token',csrf);
  }
  const r=await fetch('/api/v1'+path,{...opt,headers,credentials:'same-origin'});
  let j;try{j=await r.json()}catch{j={}}
  if(!r.ok){
    const error=Object.assign(Error(j.message||'请求失败'),{status:r.status,code:j.code,details:j.details||{}});
    if(r.status===401 && path!=='/auth/login')setSession(null);
    if(j.code==='PASSWORD_CHANGE_REQUIRED' && me)setSession({...me,must_change_password:true});
    throw error;
  }
  return j;
}
function openUrl(path){window.open('/api/v1'+path,'_blank','noopener')}
function toast(s){const t=$('#toast');t.textContent=s;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),3200)}
function esc(x){return String(x??'').replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]))}
function fmt(x){return x?new Date(x).toLocaleString('zh-CN',{hour12:false}):'-'}
function formatBytes(n){if(!n)return '0 B';if(n<1024)return n+' B';if(n<1048576)return (n/1024).toFixed(1)+' KB';return (n/1048576).toFixed(1)+' MB'}
function badge(s){const x=labels[s]||[s,'draft'];return `<span class="badge ${x[1]}">${x[0]}</span>`}
function table(headers,rows){return rows.length?`<table class="table"><thead><tr>${headers.map(h=>`<th>${h}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table>`:`<div class="empty">暂无记录</div>`}
function pager(total,page,size,handler){
  const pages=Math.max(1,Math.ceil(total/size));
  return `<div class="toolbar" style="justify-content:flex-end;margin-top:12px"><span class="muted">共 ${total} 条 · 第 ${page}/${pages} 页</span><button class="ghost" ${page<=1?'disabled':''} onclick="${handler}(${page-1})">上一页</button><button class="ghost" ${page>=pages?'disabled':''} onclick="${handler}(${page+1})">下一页</button></div>`;
}
let modalCloseHandler=null,modalDirtyCheck=null;
/**
 * 打开弹窗。
 * opts.onClose() 返回 false 可以阻止关闭（例如先把草稿存下来再关）；
 * opts.dirty() 用于"刷新/关闭标签页"前的提醒。
 * 没传 opts 时：只要弹窗里有输入控件且内容被改过，关闭前也会问一句 —— 避免手一抖点到弹窗外面就白填。
 */
function modal(title,body,opts){
  $('#modal-title').innerHTML=title;$('#modal-body').innerHTML=body;$('#modal').hidden=false;
  const o=opts||{};
  // 只把"用户真的会输入"的控件算进来：文件选择、取色器、只读/禁用控件不算，免得改个标记色也被问一句
  const inputs=[...$('#modal-body').querySelectorAll('input:not([type=file]):not([type=color]):not([type=checkbox]):not([type=radio]):not([readonly]):not([disabled]),textarea:not([readonly]):not([disabled]),select:not([disabled])')];
  const initial=inputs.map(v=>v.type==='checkbox'?String(v.checked):v.value);
  const changed=()=>inputs.some((v,i)=>(v.type==='checkbox'?String(v.checked):v.value)!==initial[i]);
  modalDirtyCheck=o.dirty||(inputs.length?changed:null);
  modalCloseHandler=o.onClose||(inputs.length?()=>changed()?confirm('弹窗里还有未保存的内容，确定关闭吗？'):true:null);
}
function hideModal(){$('#modal').hidden=true;modalCloseHandler=null;modalDirtyCheck=null}
/** 所有关闭入口（点弹窗外面、按 Esc、点关闭按钮）都走这里，先给 onClose 机会保存或拦截。 */
async function closeModal(){
  if(modalCloseHandler){let ok=false;try{ok=await modalCloseHandler('close')}catch(e){ok=false}if(!ok)return}
  hideModal();
}
/** 把校验失败渲染成一条提示：缺项多时只给汇总，不再逐字段刷屏；名字优先用字段的中文标签。 */
function fieldErrors(e){
  const details=e.details||{};
  const keys=Object.keys(details);
  if(!keys.length)return '';
  const labels=labelOf();
  const name=k=>labels[k]||k;
  const empty=keys.filter(k=>/必填/.test(String(details[k])));
  // 全部都是"没填"类错误时，给一句汇总 + 前几个字段名
  if(empty.length===keys.length&&keys.length>1){
    const sample=keys.slice(0,5).map(name).join('、');
    return `<div class="error" role="alert" style="margin-bottom:10px">还有 ${keys.length} 项信息未填写，请补全后再生成（${esc(sample)}${keys.length>5?' 等':''}）。</div>`;
  }
  if(keys.length>4){
    return `<div class="error" role="alert" style="margin-bottom:10px">共有 ${keys.length} 项未通过校验，请检查后重试（${esc(keys.slice(0,3).map(name).join('、'))} 等）。</div>`;
  }
  return `<div class="error" role="alert" style="margin-bottom:10px">${keys.map(k=>`${esc(name(k))}：${esc(details[k])}`).join('<br>')}</div>`;
}
/** 当前打开的合同/模板的字段 key -> 中文标签 */
function labelOf(){
  const map={};
  ((contractSchema&&contractSchema.fields)||[]).forEach(f=>{map[f.key]=f.label||f.key});
  return map;
}

// ---------- 动态表单：按字段类型渲染控件，计算字段只读且不回传 ----------
function control(f,value){
  const key=esc(f.key),label=esc(f.label),required=f.required?' *':'';
  const v=value===undefined||value===null?'':String(value);
  switch(f.type){
    case 'computed': return `<label>${label}<input name="${key}" value="${esc(v)}" disabled readonly></label>`;
    case 'boolean': return `<label>${label}${required}<select name="${key}"><option value="">未选择</option><option value="true" ${v==='true'?'selected':''}>${esc(f.true_label||'是')}</option><option value="false" ${v==='false'?'selected':''}>${esc(f.false_label||'否')}</option></select></label>`;
    case 'select': return `<label>${label}${required}<select name="${key}"><option value="">未选择</option>${(f.options||[]).map(o=>`<option value="${esc(o)}" ${v===o?'selected':''}>${esc(o)}</option>`).join('')}</select></label>`;
    case 'date': return `<label>${label}${required}<input type="date" name="${key}" value="${esc(v)}"></label>`;
    case 'textarea': return `<label class="full">${label}${required}<textarea name="${key}" rows="3">${esc(v)}</textarea></label>`;
    case 'money': return `<label>${label}${required}<input name="${key}" value="${esc(v)}" inputmode="decimal" placeholder="例如 10000.00"></label>`;
    default: return `<label>${label}${required}<input name="${key}" value="${esc(v)}" ${f.max_length?`maxlength="${Number(f.max_length)}"`:''}></label>`;
  }
}
function formFields(schema,data){
  const fields=(schema&&schema.fields)||[];const values=data||{};
  if(!fields.length)return '<div class="full empty">该模板尚未配置填报字段</div>';
  const groups=(schema.groups||[]);const rendered=new Set();const out=[];
  const emit=(title,list)=>{if(!list.length)return;out.push(`<div class="full"><h4>${esc(title)}</h4></div>`);list.forEach(f=>{rendered.add(f.key);out.push(control(f,values[f.key]))})};
  groups.slice().sort((a,b)=>(a.order||0)-(b.order||0)).forEach(g=>emit(g.label,fields.filter(f=>f.group===g.key)));
  emit('其他字段',fields.filter(f=>!rendered.has(f.key)));
  return out.join('');
}
function collectForm(schema){
  const data={};
  ((schema&&schema.fields)||[]).forEach(f=>{
    if(f.type==='computed')return;                       // 计算字段由后端重算，绝不回传覆盖
    const el=$('#modal-body [name="'+f.key+'"]');
    if(!el)return;
    const v=el.value;
    if(f.type==='boolean'){if(v==='')return;data[f.key]=v==='true'}   // 未选择就不提交，提交时用真正的布尔值
    else data[f.key]=v;
  });
  return data;
}
function todayLocal(){const d=new Date();return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,10)}

// ---------- 页面切换 ----------
function show(page){
  if(!me||me.must_change_password)return;
  const allowed=['dashboard','contracts'];
  if(me.role!=='USER')allowed.push('templates');
  if(me.role==='ADMIN')allowed.push('users','audit','system');
  if(!allowed.includes(page))page='dashboard';
  $$('.page').forEach(x=>x.hidden=x.id!==page);
  $$('nav a').forEach(a=>a.classList.toggle('active',a.hash==='#'+page));
  if(location.hash!=='#'+page)history.replaceState(null,'','#'+page);
  if(page==='dashboard')dashboard();
  if(page==='contracts')contracts();
  if(page==='templates')templates();
  if(page==='users')users();
  if(page==='audit')auditLogs();
  if(page==='system')systemStatus();
}
/** 重新渲染当前页面（删除/作废等操作后刷新工作台或列表）。 */
function refreshCurrentPage(){ show(location.hash.slice(1)||'dashboard') }
function setSession(user){
  me=user;
  const signedIn=!!user,changeRequired=!!user?.must_change_password;
  if(changeRequired){document.querySelectorAll('#password-change input[type=password]').forEach(i=>i.value='')}
  document.body.classList.toggle('authenticated',signedIn);
  document.body.classList.toggle('password-required',changeRequired);
  $('header').hidden=!signedIn;
  $('header nav').hidden=!signedIn||changeRequired;
  $('header .user').hidden=!signedIn;
  $('#logout').hidden=!signedIn;
  $('#who').textContent=user?.display_name||user?.username||'';
  $('#login').hidden=signedIn;
  $('#password-change').hidden=!changeRequired;
  $('#workspace').hidden=!signedIn||changeRequired;
  $$('[data-maintain]').forEach(x=>x.hidden=!signedIn||user.role==='USER');
  $$('[data-admin]').forEach(x=>x.hidden=!signedIn||user.role!=='ADMIN');
  hideModal();   // 会话变更时强制收起弹窗，不走"未保存内容"确认
  if(!signedIn){
    csrf='';contractPage=1;auditPage=1;$('#password-form').reset();$('#login-form').reset();
    $$('.page').forEach(x=>x.hidden=true);
    for(const id of ['recent','contracts-table','templates-table','users-table','audit-table','system-status'])$('#'+id).replaceChildren();
    history.replaceState(null,'',location.pathname+location.search);
  }
}
async function init(){
  // 在服务端会话有效期内自动恢复登录；过期后自动回到登录页。
  try{
    const user=await api('/auth/me');
    setSession(user);
    if(user.must_change_password)$('#password-form input').focus();
    else show(location.hash.slice(1)||'dashboard');
  }catch(e){
    setSession(null);
    if(e.status!==401)$('#login-error').textContent='连接系统失败，请稍后刷新重试';
  }
}

// ---------- 工作台 / 列表 ----------
async function dashboard(){
  try{
    const s=await api('/contracts/stats');
    $('#stat-total').textContent=s.total??0;
    $('#stat-draft').textContent=s.draft??0;
    $('#stat-final').textContent=s.finalized??0;
    $('#stat-signed').textContent=s.signed??0;
    const d=await api('/contracts?size=6&page=1');
    $('#recent').innerHTML=table(['合同','模板','状态','更新时间','操作'],(d.items||[]).map(c=>`<tr><td class="main-cell">${esc(c.title)}<div class="sub">${esc(c.contract_no)}</div></td><td>${esc(c.template_name)}<div class="sub">V${esc(c.version_no)}</div></td><td>${badge(c.status)}</td><td>${fmt(c.updated_at)}</td><td><button class="action" onclick="openContract('${c.id}')">查看</button></td></tr>`));
  }catch(e){toast(e.message)}
}
async function contracts(){
  try{
    const q=encodeURIComponent($('#contract-search')?.value||'');
    const status=$('#contract-status')?.value||'';
    const d=await api(`/contracts?q=${q}&status=${status}&size=20&page=${contractPage}`);
    if((d.items||[]).length===0&&contractPage>1){contractPage=1;return contracts()}
    $('#contracts-table').innerHTML=table(['合同','模板','状态','更新时间','操作'],(d.items||[]).map(c=>`<tr><td class="main-cell">${esc(c.title)}<div class="sub">${esc(c.contract_no)}</div></td><td>${esc(c.template_name)}<div class="sub">版本 V${esc(c.version_no)}</div></td><td>${badge(c.status)}</td><td>${fmt(c.updated_at)}</td><td><button class="action" onclick="openContract('${c.id}')">详情</button></td></tr>`))+pager(d.total||0,d.page||contractPage,20,'goContractPage');
  }catch(e){toast(e.message)}
}
function goContractPage(p){if(p<1)return;contractPage=p;contracts()}
async function templates(){
  try{
    const q=encodeURIComponent($('#template-search')?.value||'');
    const category=encodeURIComponent($('#template-category')?.value||'');
    const d=await api(`/templates?q=${q}&category=${category}&size=100`);
    const cats=[...new Set((d.items||[]).map(x=>x.category))];
    $('#template-category').innerHTML='<option value="">全部分类</option>'+cats.map(x=>`<option ${x===$('#template-category').value?'selected':''}>${esc(x)}</option>`).join('');
    $('#templates-table').innerHTML=table(['模板','分类','当前版本','状态','维护人','更新时间','操作'],(d.items||[]).map(t=>`<tr><td class="main-cell">${esc(t.name)}<div class="sub">${esc(t.archived_code||t.code)}</div></td><td>${esc(t.category)}</td><td>${t.current_version_no?`V${esc(t.current_version_no)}`:'待整理'}</td><td>${t.status==='ACTIVE'?'<span class="badge signed">启用</span>':'<span class="badge void">停用</span>'}</td><td>${esc(t.maintainer_name)}</td><td>${fmt(t.updated_at)}</td><td><button class="action" onclick="openTemplate('${t.id}')">详情</button> <button class="action" onclick="toggleTemplate('${t.id}',${Number(t.lock_version)},'${esc(t.status)}')">${t.status==='ACTIVE'?'停用':'启用'}</button></td></tr>`));
  }catch(e){toast(e.message)}
}
async function users(){
  try{
    const a=await api('/users');
    $('#users-table').innerHTML=table(['账号','姓名','类别','状态','创建时间','操作'],a.map(u=>`<tr><td class="main-cell">${esc(u.username)}</td><td>${esc(u.display_name)}</td><td><select onchange="changeRole('${u.id}',this.value)"><option value="USER" ${u.role==='USER'?'selected':''}>普通用户</option><option value="CONTRACT_MAINTAINER" ${u.role==='CONTRACT_MAINTAINER'?'selected':''}>合同维护员</option><option value="ADMIN" ${u.role==='ADMIN'?'selected':''}>管理员</option></select></td><td>${u.enabled?'<span class="badge signed">启用</span>':'<span class="badge void">停用</span>'}</td><td>${fmt(u.created_at)}</td><td><button class="action" onclick="editUser('${u.id}',${u.enabled})">${u.enabled?'停用':'启用'}</button> <button class="action" onclick="resetPassword('${u.id}')">重置密码</button> <button class="action danger" onclick="deleteUser('${u.id}')">删除</button></td></tr>`));
  }catch(e){toast(e.message)}
}
async function auditLogs(){
  try{
    const params=new URLSearchParams({q:$('#audit-q')?.value||'',actor:$('#audit-actor')?.value||'',action:$('#audit-action')?.value||'',objectType:$('#audit-object')?.value||'',from:$('#audit-from')?.value||'',to:$('#audit-to')?.value||'',size:'50',page:String(auditPage)});
    const d=await api('/audit-logs?'+params.toString());
    $('#audit-table').innerHTML=table(['时间','操作人','动作','对象','请求编号'],(d.items||[]).map(x=>`<tr><td>${fmt(x.created_at)}</td><td>${esc(x.username||'系统')}</td><td>${esc(x.action)}</td><td>${esc(x.object_type)} ${esc(x.object_id||'')}</td><td class="sub">${esc(x.request_id)}</td></tr>`))+pager(d.total||0,d.page||auditPage,50,'goAuditPage');
  }catch(e){toast(e.message)}
}
function goAuditPage(p){if(p<1)return;auditPage=p;auditLogs()}
function resetAudit(){for(const id of ['audit-q','audit-actor','audit-action','audit-object','audit-from','audit-to'])$('#'+id).value='';auditPage=1;auditLogs()}
async function systemStatus(){
  try{
    const x=await api('/system/status');
    const rows=[['数据库','正常'],['可用存储',Math.round((x.storage_free_bytes||0)/1073741824)+' GB'],['转换服务',x.converter_available?'可用':'不可用'],['并发转换数',x.worker_concurrency??'-'],['排队任务',x.queued_jobs],['运行任务',x.running_jobs],['成功任务',x.succeeded_jobs],['失败任务',x.failed_jobs]];
    $('#system-status').innerHTML=rows.map(a=>`<div><span>${a[0]}</span><strong>${esc(a[1])}</strong></div>`).join('')+`<div><span>最近成功生成</span><strong>${fmt(x.last_succeeded_at)}</strong></div>`;
  }catch(e){toast(e.message)}
}

// ---------- 合同填报与全流程 ----------
async function newContract(){
  try{
    const d=await api('/templates?size=100');
    const available=(d.items||[]).filter(t=>t.status==='ACTIVE'&&t.current_version_id&&t.current_version_no);
    if(!available.length){modal('暂时不能新建合同','<div class="empty">当前没有可用模板。请先在“模板中心”上传 DOCX、完成字段配置、试填并发布一个模板。</div><div class="form-actions"><button class="primary" data-action="close-modal">知道了</button></div>');return}
    const opts=available.map(t=>`<option value="${esc(t.current_version_id)}">${esc(t.name)}（V${esc(t.current_version_no)}）</option>`).join('');
    modal('新建合同',`<form id="new-contract-form" class="form-grid"><label class="full">合同名称<input name="title" required maxlength="150"></label><label class="full">选择模板<select name="template_version_id" required>${opts}</select></label><div class="form-actions full"><button type="button" class="ghost" data-action="close-modal">取消</button><button class="primary">创建并开始填报</button></div></form>`);
    $('#new-contract-form').onsubmit=async e=>{
      e.preventDefault();
      try{const c=await api('/contracts',{method:'POST',body:JSON.stringify(Object.fromEntries(new FormData(e.target)))});hideModal();openContract(c.id)}
      catch(x){toast(x.message)}
    };
  }catch(e){toast(e.message)}
}
function jobNotice(c){
  if(!c.job_state)return '';
  if(c.job_state==='FAILED')return `<div class="error" role="alert" style="margin-bottom:10px">上次生成失败：${esc(c.job_error||'未知错误')}。可再次点击“生成全文预览”。</div>`;
  if(c.current_revision_matches_draft===false)return `<div class="muted" style="margin-bottom:10px">草稿在生成后被修改过，当前全文不是最新内容，请重新生成后再定稿。</div>`;
  return '';
}
function signedSection(c){
  const list=c.signed_attachments||[];
  if(!list.length)return '';
  const rows=list.map(a=>`<tr><td>${esc(a.signed_on)}${a.voided_at?' <span class="badge void">已作废</span>':''}<div class="sub">${esc(a.original_name)} · ${formatBytes(a.byte_size)}</div></td><td class="sub">${esc(a.note||'-')}${a.void_reason?`<div class="sub">作废原因：${esc(a.void_reason)}</div>`:''}</td><td><button class="action" onclick="signedDownload('${c.id}','${a.id}')">下载</button>${a.voided_at?'':` <button class="action danger" onclick="signedVoid('${c.id}','${a.id}')">作废</button>`}</td></tr>`).join('');
  return `<div class="full"><h4>签署件归档</h4></div><div class="full">${table(['签署日期 / 文件','备注','操作'],[rows])}</div>`;
}
async function openContract(id){
  try{
    const c=await api('/contracts/'+id);
    contractDetail=c;
    contractSchema=await api('/template-versions/'+c.template_version_id+'/form-schema');
    const draft=c.status==='DRAFT';
    const rid=c.current_revision_id||'';
    const ready=draft&&rid&&c.job_state==='SUCCEEDED'&&c.current_revision_matches_draft;
    const outdated=draft&&c.latest_version_id&&c.latest_version_id!==c.template_version_id;
    const body=`
      <div class="form-grid">
        <label>合同名称<input id="edit-title" value="${esc(c.title)}" ${draft?'':'disabled'}></label>
        <div><b>合同编号</b><p>${esc(c.contract_no)}</p></div>
        <div><b>模板版本</b><p>V${esc(c.version_no)}${outdated?` <span class="badge draft">可升级到 V${esc(c.latest_version_no)}</span>`:''}</p></div>
        <div><b>状态</b><p>${badge(c.status)}</p></div>
        <div><b>当前修订</b><p>${c.current_revision_no?('R'+esc(c.current_revision_no)+(c.job_state?` · ${esc(JOB_TEXT[c.job_state]||c.job_state)}`:'')):'—'}</p></div>
      </div>
      ${jobNotice(c)}
      <div class="form-grid">${formFields(contractSchema,c.form_data||{})}</div>
      ${draft?'':signedSection(c)}
      <div class="form-actions">
        <button class="ghost" data-action="close-modal">关闭</button>
        ${draft?`
          <button class="ghost danger" onclick="purgeContract('${id}',${Number(c.lock_version)},false)">彻底删除草稿</button>
          ${outdated?`<button class="ghost" onclick="upgradeContract('${id}')">升级到 V${esc(c.latest_version_no)}</button>`:''}
          <button class="ghost" onclick="saveContract('${id}',${Number(c.lock_version)})">保存草稿</button>
          <button class="primary" onclick="generateContract('${id}',${Number(c.lock_version)})">${rid?'重新生成全文':'生成全文预览'}</button>
          ${ready?`<button class="primary" onclick="openPreview('${id}','${rid}')">查看全文 PDF</button>
          <button class="primary" onclick="finalizeContract('${id}','${rid}',${Number(c.lock_version)})">确认全文并定稿</button>`:''}
        `:`
          <button class="ghost" onclick="downloadContract('${id}','docx')">下载 Word</button>
          <button class="primary" onclick="downloadContract('${id}','pdf')">下载 PDF</button>
          ${c.status!=='VOID'?`<button class="ghost" onclick="signedDialog('${id}')">登记已签署</button><button class="ghost danger" onclick="voidContract('${id}')">作废</button>`:''}
          <button class="ghost" onclick="deriveContract('${id}')">派生新草稿</button>
          ${c.status==='VOID'&&me?.role==='ADMIN'?`<button class="ghost danger" onclick="purgeContract('${id}',${Number(c.lock_version)},true)">彻底删除已作废合同</button>`:''}
        `}
        ${me?.role==='ADMIN'?`<button class="ghost" onclick="reassignOwner('${id}')">转移所有者</button>`:''}
      </div>`;
    modal(`合同详情 · ${esc(c.contract_no)}`,body,{onClose:contractCloseGuard(id,c,draft),dirty:()=>contractFormDirty(c,contractSchema,draft)});
  }catch(e){toast(e.message)}
}
const contractFormDirty=(c,schema,draft)=>{
  if(!draft||!$('#edit-title'))return false;
  const form=collectForm(schema),before=c.form_data||{};
  const title=$('#edit-title').value;
  return title!==c.title||Object.keys(form).some(k=>String(form[k]??'')!==String(before[k]??''));
};
/**
 * 填报页的关闭守卫：点弹窗外面/按 Esc/点关闭时，只要填过东西就先把草稿存下来再关。
 * 早期版本直接关掉弹窗，一次误点就要重填一页，所以这里改成"自动保存 + 关闭"；
 * 保存失败（并发冲突等）就不关，把错误显示在页面上，绝不静默丢内容。
 */
function contractCloseGuard(id,c,draft){
  let closing=false;
  return async ()=>{
    if(closing||!contractFormDirty(c,contractSchema,draft))return true;
    closing=true;
    try{
      await api('/contracts/'+id,{method:'PATCH',body:JSON.stringify({title:$('#edit-title').value,expected_lock_version:Number(c.lock_version),form_data:collectForm(contractSchema)})});
      toast('已自动保存为草稿，下次打开可继续填写');contracts();
      return true;
    }catch(e){
      closing=false;
      const el=$('#modal-body');if(el)el.insertAdjacentHTML('afterbegin',fieldErrors(e));
      // 保存不了也不能把用户困在弹窗里：给一个明确的"放弃修改并关闭"出口。
      return confirm('自动保存失败：'+e.message+'\n\n点【确定】放弃本次修改并关闭；点【取消】留在页面上继续修改（可以点“保存草稿”重试）。');
    }
  };
}
async function saveContract(id,lock){
  try{
    const body={title:$('#edit-title').value,expected_lock_version:lock,form_data:collectForm(contractSchema)};
    await api('/contracts/'+id,{method:'PATCH',body:JSON.stringify(body)});
    toast('草稿已保存');openContract(id);contracts();
  }catch(e){const el=$('#modal-body');if(el)el.insertAdjacentHTML('afterbegin',fieldErrors(e));toast(e.message)}
}
/**
 * 生成全文。服务端校验的是"已保存的草稿"，所以这里先把当前表单存下来（只有内容变化才存，
 * 避免无谓提升 lock_version 破坏"同快照复用生成"），再发起生成。
 */
async function generateContract(id,lock){
  try{
    const form=collectForm(contractSchema);
    const before=(contractDetail&&contractDetail.form_data)||{};
    const title=$('#edit-title')?$('#edit-title').value:((contractDetail&&contractDetail.title)||'');
    const changed=Object.keys(form).some(k=>String(form[k]??'')!==String(before[k]??''))||title!==((contractDetail&&contractDetail.title)||'');
    if(changed){
      const saved=await api('/contracts/'+id,{method:'PATCH',body:JSON.stringify({title,expected_lock_version:lock,form_data:form})});
      toast('已保存草稿（修订 '+(saved.lock_version??'')+'），正在生成全文…');
    }
    const j=await api('/contracts/'+id+'/generate',{method:'POST',body:'{}'});
    if(!changed)toast(j.reused?'内容未变，已复用上次生成的全文':'已加入生成队列');
    pollJob(j.job.id,id,j.revision_id);
  }catch(e){const el=$('#modal-body');if(el)el.insertAdjacentHTML('afterbegin',fieldErrors(e));toast('生成失败：'+e.message)}
}
async function pollJob(jobId,contractId,revisionId){
  for(let i=0;i<90;i++){
    await new Promise(r=>setTimeout(r,2000));
    let j;try{j=await api('/generation-jobs/'+jobId)}catch(e){return}
    if(j.state==='SUCCEEDED'){
      toast('全文生成完成，请点击“查看全文 PDF”检查后再定稿');
      if(contractId)openContract(contractId);
      else if(revisionId)openPreview(contractId,revisionId);
      return;
    }
    if(j.state==='FAILED'){toast('生成失败：'+(j.error_code||'未知错误'));if(contractId)openContract(contractId);return}
  }
  toast('生成仍在排队或转换中，请稍后刷新查看');
}
function openPreview(contractId,revisionId){
  if(!revisionId)return toast('请先生成全文');
  openUrl(`/contracts/${contractId}/revisions/${revisionId}/preview`);
}
async function finalizeContract(id,rid,lock){
  if(!confirm('定稿后合同内容将冻结、不能直接修改。确认已完整查看 PDF 并定稿？'))return;
  try{
    const r=await api(`/contracts/${id}/revisions/${rid}/review`,{method:'POST',body:JSON.stringify({confirmed:true})});
    await api(`/contracts/${id}/finalize`,{method:'POST',body:JSON.stringify({revision_id:rid,review_receipt_id:r.review_receipt_id,expected_lock_version:lock})});
    toast('合同已定稿，可下载正式文件');openContract(id);contracts();
  }catch(e){toast('定稿失败：'+e.message)}
}
function downloadContract(id,format){openUrl(`/contracts/${id}/download?format=${format}`)}
async function upgradeContract(id){
  try{
    const p=await api(`/contracts/${id}/upgrade-preview`,{method:'POST',body:'{}'});
    if(p.up_to_date)return toast('当前已是最新模板版本');
    upgradeSchema=p.schema;
    const c=await api('/contracts/'+id);
    modal(`模板版本升级：V${esc(p.from_version)} → V${esc(p.to_version)}`,
      `<p class="muted">以下内容已按新版本字段配置迁移，请核对后确认。字段被改名时按模板的迁移映射处理。</p>
       <div class="form-grid">${formFields(p.schema,p.data||{})}</div>
       <div class="form-actions"><button class="ghost" data-action="close-modal">取消</button><button class="primary" onclick="submitUpgrade('${id}',${Number(c.lock_version)},'${p.target_template_version_id}')">确认升级</button></div>`);
  }catch(e){toast('升级预览失败：'+e.message)}
}
async function submitUpgrade(id,lock,targetVersionId){
  try{
    await api(`/contracts/${id}/upgrade`,{method:'POST',body:JSON.stringify({target_template_version_id:targetVersionId,expected_lock_version:lock,form_data:collectForm(upgradeSchema)})});
    toast('已升级到最新模板版本');openContract(id);contracts();
  }catch(e){const el=$('#modal-body');if(el)el.insertAdjacentHTML('afterbegin',fieldErrors(e));toast('升级失败：'+e.message)}
}
/** 彻底删除合同：物理删除合同、修订、生成文件与签署件；审计记录保留。 */
async function purgeContract(id,lock,isVoid){
  const head=isVoid
    ? '确定彻底删除这份【已作废】合同吗？\n\n· 合同、全部修订、生成的 Word/PDF、签署件都会被物理删除，不可恢复\n· 仅保留审计记录（谁在什么时候删了什么、原因）\n· 需要管理员权限'
    : '确定彻底删除这份草稿吗？\n\n· 合同、全部修订与生成的 Word/PDF 都会被物理删除，不可恢复\n· 仅保留审计记录';
  if(!confirm(head))return;
  const reason=prompt('请输入删除原因（会记入审计）');if(!reason)return;
  try{
    const r=await api('/contracts/'+id,{method:'DELETE',body:JSON.stringify({expected_lock_version:lock,reason})});
    toast('已彻底删除（修订 '+(r.revisions??0)+' · 任务 '+(r.jobs??0)+' · 文件 '+(r.files??0)+'）');
    hideModal();refreshCurrentPage();
  }catch(e){toast('删除失败：'+e.message)}
}
async function voidContract(id){
  const reason=prompt('请输入作废原因（将保留全部历史）');if(!reason)return;
  try{await api(`/contracts/${id}/void`,{method:'POST',body:JSON.stringify({reason})});toast('合同已作废');openContract(id);contracts()}
  catch(e){toast('作废失败：'+e.message)}
}
async function deriveContract(id){
  try{const c=await api(`/contracts/${id}/derive`,{method:'POST',body:'{}'});toast('已派生新草稿');openContract(c.id);contracts()}
  catch(e){toast('派生失败：'+e.message)}
}
function signedDialog(id){
  modal('登记已签署',`<div class="form-grid">
    <label class="full">签署日期<input type="date" id="signed-on" required></label>
    <label class="full">签署 PDF<input type="file" id="signed-file" accept=".pdf,application/pdf" required></label>
    <label class="full">备注<input id="signed-note" maxlength="1000" placeholder="补传时请说明原因"></label>
    <div class="form-actions full"><button class="ghost" data-action="close-modal">取消</button><button class="primary" onclick="submitSigned('${id}')">归档签署件</button></div>
  </div>`);
  $('#signed-on').value=todayLocal();
}
async function submitSigned(id){
  const file=$('#signed-file').files?.[0],on=$('#signed-on').value;
  if(!file)return toast('请选择签署 PDF 文件');
  if(!on)return toast('请填写签署日期');
  const fd=new FormData();fd.append('file',file);fd.append('signed_on',on);fd.append('note',$('#signed-note').value||'');
  try{await api(`/contracts/${id}/signed-attachments`,{method:'POST',body:fd});toast('签署件已归档');openContract(id);contracts()}
  catch(e){toast('归档失败：'+e.message)}
}
function signedDownload(id,aid){openUrl(`/contracts/${id}/signed-attachments/${aid}/download`)}
async function signedVoid(id,aid){
  const reason=prompt('请输入作废该签署件的原因');if(!reason)return;
  try{await api(`/contracts/${id}/signed-attachments/${aid}/void`,{method:'POST',body:JSON.stringify({reason})});toast('签署件已作废');openContract(id)}
  catch(e){toast('作废失败：'+e.message)}
}
async function reassignOwner(id){
  try{
    const list=await api('/users');
    const candidates=list.filter(u=>u.enabled);
    modal('转移合同所有者',`<div class="form-grid"><label class="full">新所有者<select id="new-owner">${candidates.map(u=>`<option value="${u.id}">${esc(u.display_name)}（${esc(u.username)}）</option>`).join('')}</select></label><label class="full">原因<input id="owner-reason" maxlength="1000" placeholder="例如：原负责人离职"></label><div class="form-actions full"><button class="ghost" data-action="close-modal">取消</button><button class="primary" onclick="submitOwner('${id}')">确认转移</button></div></div>`);
  }catch(e){toast(e.message)}
}
async function submitOwner(id){
  const owner=$('#new-owner').value,reason=$('#owner-reason').value;
  if(!reason)return toast('请填写转移原因');
  try{await api(`/contracts/${id}/owner`,{method:'POST',body:JSON.stringify({owner_id:owner,reason})});toast('所有者已变更');openContract(id);contracts()}
  catch(e){toast('转移失败：'+e.message)}
}

// ---------- 模板中心 ----------
async function openTemplate(id){
  try{
    const t=await api('/templates/'+id);
    const versions=t.versions||[];
    const rows=versions.map(v=>{
      const doc=v.docx_file_name?`当前 DOCX：${esc(v.docx_file_name)}（${formatBytes(v.docx_file_size)}）`:'未上传 DOCX 母版';
      const pdf=v.pdf_file_name?`当前 PDF：${esc(v.pdf_file_name)}（${formatBytes(v.pdf_file_size)}）`:'未上传 PDF 原件';
      const lock=Number(v.lock_version);
      const name=v.change_note?`<span class="sub">名称：${esc(v.change_note)}</span>`:'<span class="sub">未命名</span>';
      return `<div class="version-row"><div class="version-meta"><b>V${esc(v.version_no)}</b><span>${v.state==='PUBLISHED'?'已发布':'草稿'}</span>${name}<span class="sub">${doc} · ${pdf}</span></div>${v.state==='DRAFT'?`<label class="file-pick">上传/替换文件<input type="file" accept=".docx,.pdf,application/pdf" onchange="uploadTemplateFile('${v.id}',this,'${id}')"></label><label class="file-pick" title="母版里没有 {{占位符}} 时：用此颜色标记的文字会被自动识别成字段；连续 3 个以上下划线也会被识别。请在 Word 里用『字体颜色 → 其他颜色 → 自定义』输入同一色值（不要用主题颜色）。">标记色<input type="color" id="marker-${v.id}" value="${markerColorDefault()}" onchange="rememberMarkerColor(this.value)"></label>`:''}${v.state==='DRAFT'&&v.docx_file_id?`<button class="action" onclick="validateTemplateVersion('${v.id}')">校验</button><button class="action" onclick="editTemplateSchema('${v.id}','${id}')">字段配置</button><button class="action" onclick="testTemplateVersion('${v.id}','${id}')">试填</button>${v.test_job_id?`<button class="action" onclick="previewTemplateTest('${v.id}')">查看试填 PDF</button><button class="action" onclick="confirmTemplateTest('${v.id}','${id}')">确认试填</button>`:''}<button class="action" onclick="publishTemplateVersion('${v.id}','${id}',${lock})">发布</button>`:''}${v.docx_file_id?`<button class="action" onclick="downloadSource('${v.id}','docx')">Word</button>`:''}${v.original_docx_file_id?`<button class="action" onclick="downloadSource('${v.id}','original')" title="下划线空白被自动改写前上传的原始文件">原始上传件</button>`:''}${v.source_pdf_file_id?`<button class="action" onclick="downloadSource('${v.id}','pdf')">PDF</button>`:''}<button class="action" onclick="renameVersion('${v.id}','${id}')">重命名</button><button class="action" onclick="autoFillLabels('${v.id}','${id}')">字段名中文化</button>${v.state==='DRAFT'?`<button class="action danger" onclick="deleteTemplateVersion('${v.id}','${id}',${lock})">彻底删除版本</button>`:''}</div>`;
    }).join('');
    modal(`模板详情 · ${esc(t.name)}`,`<div class="form-grid"><div><b>模板编号</b><p>${esc(t.archived_code||t.code)}</p></div><div><b>当前版本</b><p>${t.current_version_no?`V${esc(t.current_version_no)}`:'待整理'}</p></div><div><b>状态</b><p>${t.status==='ACTIVE'?'启用':'停用'}</p></div><div class="full"><h4>版本与文件</h4><div>${rows||'<div class="empty">暂无版本，请创建版本</div>'}</div></div></div><div class="form-actions"><button class="ghost" data-action="close-modal">关闭</button><button class="ghost" onclick="createTemplateVersion('${t.id}')">创建版本</button><button class="ghost danger" onclick="deleteTemplate('${t.id}',${Number(t.lock_version)})">删除模板</button></div>`);
  }catch(e){toast('打开模板详情失败：'+e.message)}
}
async function createTemplateVersion(id){
  const note=prompt('请输入版本说明（可留空）','');if(note===null)return;
  try{await api(`/templates/${id}/versions`,{method:'POST',body:JSON.stringify({change_note:note})});toast('版本草稿已创建');openTemplate(id)}
  catch(e){toast('创建版本失败：'+e.message)}
}
async function toggleTemplate(id,lock,status){
  const next=status==='ACTIVE'?'DISABLED':'ACTIVE';
  if(!confirm(next==='DISABLED'?'确定停用这个模板吗？停用后不能新建合同（已存在的草稿仍可填报和生成）。':'确定重新启用这个模板吗？'))return;
  try{await api('/templates/'+id,{method:'PATCH',body:JSON.stringify({status:next,expected_lock_version:lock})});toast(next==='DISABLED'?'模板已停用':'模板已启用');templates()}
  catch(e){toast('状态更新失败：'+e.message)}
}
async function validateTemplateVersion(id){
  try{await api('/template-versions/'+id+'/validate',{method:'POST',body:'{}'});toast('模板校验通过')}
  catch(e){toast('校验失败：'+e.message)}
}
async function editTemplateSchema(versionId,templateId){
  try{
    const v=await api('/template-versions/'+versionId);
    const schema=v.field_schema||{schema_version:1,groups:[{key:'base',label:'合同信息',order:1}],fields:[]};
    const empty=!((schema.fields||[]).length);
    const hint=empty?'<p class="muted full">当前母版里没有 <b>{{占位符}}</b>，所以没有可配置字段。请先在 Word 中把需要填写的内容改写成 {{字段名}}（例如 {{party_b_name}}），重新上传 DOCX 后再回来配置。</p>':'';
    // 填充位置核对表：每个字段会填到母版的哪几处、附近文字是什么
    const occ=v.occurrences||{};
    const flds=(schema.fields||[]);
    const configured=new Set(flds.map(f=>f.key));
    const rows=flds.map(f=>{
      const list=occ[f.key]||[];
      const computed=f.type==='computed';
      const state=computed?'<span class="badge signed">自动计算</span>':(list.length?`<span class="badge signed">${list.length} 处</span>`:'<span class="badge void">母版中没找到</span>');
      const ctx=list.length?esc(String(list[0].context||'').replace(/\s+/g,' ').slice(0,60)):'—';
      return `<tr><td>${esc(f.label||f.key)}<div class="sub">${esc(f.key)}</div></td><td>${state}</td><td class="sub">…${ctx}…</td></tr>`;
    });
    const unconfigured=Object.keys(occ).filter(k=>!configured.has(k));
    const warnList=unconfigured.length?`<p class="error full" role="alert">母版里有这些占位符但配置里没有：${esc(unconfigured.join('、'))}（校验会报"未配置"）。点下面【按母版同步字段】或重新上传母版即可自动补上。</p>`:'';
    // 配置里有、母版里没有的字段：绑定校验会直接失败（换过母版/复制过版本后最常见）
    const stale=flds.filter(f=>!occ[f.key]);
    const staleList=stale.length?`<p class="error full" role="alert">配置里有 ${stale.length} 个字段在母版中不存在：${esc(stale.map(f=>f.key).join('、'))}。试填/发布时会报"字段绑定不匹配"。<button class="action" onclick="syncTemplateFields('${versionId}','${templateId}',${Number(v.lock_version)})">按母版同步字段</button></p>`:'';
    const posTable=flds.length?`<div class="full"><h4>填充位置核对（共 ${flds.length} 个字段）</h4>${table(['字段','母版中出现','首处附近文字'],[rows])}${warnList}${staleList}</div>`:'';
    modal('字段配置',`<div class="form-grid">${hint}${posTable}<label class="full">字段 JSON（可编辑）<textarea id="schema-json" class="json-editor">${esc(JSON.stringify(schema,null,2))}</textarea></label></div><div class="form-actions"><button class="ghost" data-action="close-modal">取消</button><button class="ghost" onclick="syncTemplateFields('${versionId}','${templateId}',${Number(v.lock_version)})" title="母版是字段的来源：母版里新增的占位符自动补成字段，母版里已不存在的字段自动删掉">按母版同步字段</button><button class="primary" onclick="saveTemplateSchema('${versionId}','${templateId}',${Number(v.lock_version)})">保存字段配置</button></div>`);
  }catch(e){toast(e.message)}
}
async function syncTemplateFields(versionId,templateId,lock){
  if(!confirm('按母版重新对齐字段配置？\n\n· 母版里新增的占位符 → 自动补成字段\n· 母版里已不存在的字段 → 从配置里删掉（含中文名与校验设置）\n\n只影响这个草稿版本，不影响已发布版本和历史合同。'))return;
  try{
    const res=await api('/template-versions/'+versionId+'/sync-fields',{method:'POST',body:JSON.stringify({expected_lock_version:lock})});
    const notes=res.repairedFieldNotes||[];
    toast('字段已按母版对齐：新增 '+(res.addedFields||0)+' 个、删除 '+(res.removedFields||0)+' 个'+(notes.length?'、修复 '+notes.length+' 处引用（'+notes.join('；')+'）':''));
    editTemplateSchema(versionId,templateId);
  }catch(e){toast('同步失败：'+e.message)}
}
async function saveTemplateSchema(id,templateId,lock){
  try{
    const schema=JSON.parse($('#schema-json').value);
    await api('/template-versions/'+id+'/schema',{method:'PUT',body:JSON.stringify({field_schema:schema,expected_lock_version:lock})});
    toast('字段配置已保存');openTemplate(templateId);
  }catch(e){toast('字段配置保存失败：'+e.message)}
}
async function testTemplateVersion(id,templateId){
  try{
    const v=await api('/template-versions/'+id);
    const schema=v.field_schema||{};
    const values={};
    (schema.fields||[]).forEach(f=>{
      if(f.type==='computed')return;
      if(f.type==='boolean'){values[f.key]=(f.default===true)}
      else if(f.type==='select'){values[f.key]=f.default??((f.options||[])[0]||'')}
      else if(f.type==='date'){values[f.key]=f.default??todayLocal()}
      else if(f.type==='money'){values[f.key]=f.default??(f.min??'0.00')}     // 金额用字符串
      else if(f.type==='integer'){values[f.key]=f.default??(f.min??0)}        // 整数必须是整数，给小数会被服务端拒绝
      else {values[f.key]=f.default??'测试内容'}
    });
    const j=await api('/template-versions/'+id+'/test-render',{method:'POST',body:JSON.stringify({expected_lock_version:Number(v.lock_version),form_data:values})});
    toast('试填已加入生成队列');
    pollTemplateTest(j.id,id,templateId);
  }catch(e){toast('试填失败：'+e.message)}
}
async function pollTemplateTest(jobId,versionId,templateId){
  for(let i=0;i<90;i++){
    await new Promise(r=>setTimeout(r,2000));
    let j;try{j=await api('/generation-jobs/'+jobId)}catch(e){toast('试填状态查询失败：'+e.message);return}
    if(j.state==='SUCCEEDED'){
      toast('试填生成完成，请打开 PDF 检查版式后点击“确认试填”');
      openTemplate(templateId);
      return;
    }
    if(j.state==='FAILED'){toast('试填生成失败：'+(j.error_code||'未知错误'));return}
  }
  toast('试填仍在排队或转换中，请稍后刷新查看');
}
function previewTemplateTest(versionId){openUrl('/template-versions/'+versionId+'/test-preview')}
async function confirmTemplateTest(versionId,templateId){
  try{await api('/template-versions/'+versionId+'/test-review',{method:'POST',body:JSON.stringify({confirmed:true})});toast('试填已确认，可以发布');openTemplate(templateId)}
  catch(e){toast('确认试填失败：'+e.message)}
}
async function publishTemplateVersion(id,templateId,lock){
  if(!confirm('确认已检查试填结果并发布此版本吗？发布后该版本内容不可修改。'))return;
  try{await api('/template-versions/'+id+'/publish',{method:'POST',body:JSON.stringify({expected_lock_version:lock})});toast('模板版本已发布');openTemplate(templateId);templates()}
  catch(e){toast('发布失败：'+e.message)}
}
function downloadSource(id,format){openUrl('/template-versions/'+id+'/source?format='+format)}
/** 标记色：记住上次选择，省得每次都要挑。 */
function markerColorDefault(){ try{ return localStorage.getItem('qh_marker_color')||'#ff0000' }catch(e){ return '#ff0000' } }
function rememberMarkerColor(v){ try{ localStorage.setItem('qh_marker_color',v) }catch(e){} toast('标记色已记住：'+v+'（在 Word 里用同一色值标记待填文字）') }
/** 把仍是英文 key 的字段显示名改成内置中文名（只改显示名，不动类型/必填/绑定）。 */
async function autoFillLabels(versionId,templateId){
  if(!confirm('把该版本里"显示名还是英文 key"的字段改成内置中文名吗？\n\n· 只改显示名（填报界面与校验提示立刻变中文）\n· 不改字段类型、必填设置和取值绑定，也不影响已生成的正文'))return;
  try{
    const v=await api('/template-versions/'+versionId+'/labels',{method:'POST',body:'{}'});
    toast('已填中文名：改了 '+(v.changed??0)+' 个字段');
    openTemplate(templateId);
  }catch(e){toast('填中文名失败：'+e.message)}
}
/** 给版本命名（纯元数据，不影响已完成的试填确认）。 */
async function renameVersion(versionId,templateId){
  const v=await api('/template-versions/'+versionId).catch(()=>null);
  const name=prompt('给这个版本起个名字（最多 200 字，留空表示清除）',v?v.change_note||'':'');if(name===null)return;
  try{await api('/template-versions/'+versionId+'/note',{method:'PATCH',body:JSON.stringify({change_note:name})});toast('版本名称已保存');openTemplate(templateId)}
  catch(e){toast('重命名失败：'+e.message)}
}
/** 彻底删除版本草稿：物理删除，版本号会被释放，下一个新版本可以重新使用该号。 */
async function deleteTemplateVersion(versionId,templateId,lock){
  if(!confirm('确定彻底删除这个草稿版本吗？\n\n· 版本行、试填任务与试填产物会被物理删除，不可恢复\n· 只被它引用的母版/原件文件会被回收\n· 已发布、被合同引用或有运行中任务的版本不允许删除\n· 该版本号会被释放；若它高于当前已发布版本号，下个新版本会重新使用它'))return;
  const reason=prompt('请输入删除原因（会记入审计）');if(!reason)return;
  try{
    const r=await api('/template-versions/'+versionId,{method:'DELETE',body:JSON.stringify({expected_lock_version:lock,reason})});
    toast('版本 V'+(r.version_no??'')+' 已彻底删除，版本号可重新使用');
    openTemplate(templateId);
  }catch(e){toast('删除版本失败：'+e.message)}
}
async function deleteTemplate(id,lock){
  if(!confirm('确定彻底删除整个模板吗？\n\n· 模板、它的全部版本（含已发布版本）、试填任务与母版文件都会被物理删除，不可恢复\n· 只保留审计记录\n· 如果已有合同引用该模板会被拒绝——那种情况请改用「停用」'))return;
  const reason=prompt('请输入删除原因（会记入审计）');if(!reason)return;
  try{
    const r=await api('/templates/'+id,{method:'DELETE',body:JSON.stringify({expected_lock_version:lock,reason})});
    toast('模板已彻底删除（版本 '+(r.versions??0)+' · 任务 '+(r.jobs??0)+' · 文件 '+(r.files??0)+'）');
    hideModal();templates();
  }catch(e){toast('删除模板失败：'+e.message)}
}
async function uploadTemplateFile(versionId,input,templateId){
  const file=input.files?.[0];if(!file)return;
  try{
    const v=await api('/template-versions/'+versionId);
    const colorEl=$('#marker-'+versionId);
    const marker=((colorEl&&colorEl.value)||'#ff0000').replace('#','');
    const fd=new FormData();fd.append('file',file);fd.append('expected_lock_version',String(Number(v.lock_version)));fd.append('marker_color',marker);
    const res=await api('/template-versions/'+versionId+'/files',{method:'POST',body:fd});
    if(res&&res.warning)toast(res.warning);
    else if(res&&res.autoFields)toast('已自动识别 '+res.autoFields+' 处标记并转成字段（颜色 '+(res.autoColorFields||0)+' 处 / 下划线 '+(res.autoBlankFields||0)+' 处），请到「字段配置」核对中文名与类型');
    else if(res&&(res.addedFields||res.removedFields))toast('字段配置已按母版自动对齐：新增 '+(res.addedFields||0)+' 个、删除 '+(res.removedFields||0)+' 个（母版里已不存在），请到「字段配置」核对');
    else toast('文件上传成功');
    openTemplate(templateId);
  }catch(e){toast(e.message)}finally{input.value=''}
}

// ---------- 账号 ----------
function newUser(){
  modal('新增账号',`<form id="new-user-form" class="form-grid"><label>登录账号<input name="username" required maxlength="64"></label><label>姓名<input name="display_name" required maxlength="100"></label><label>用户类别<select name="role"><option value="USER">普通用户</option><option value="CONTRACT_MAINTAINER">合同维护员</option></select></label><label>临时密码<input name="password" placeholder="留空自动生成" minlength="6"></label><div class="form-actions full"><button type="button" class="ghost" data-action="close-modal">取消</button><button class="primary">创建账号</button></div></form>`);
  $('#new-user-form').onsubmit=async e=>{
    e.preventDefault();
    try{
      const u=await api('/users',{method:'POST',body:JSON.stringify(Object.fromEntries(new FormData(e.target)))});
      modal('账号已创建',`<div class="form-grid"><p class="full">请将临时密码安全交给用户，首次登录必须修改。</p><div class="full"><b>临时密码</b><input readonly value="${esc(u.temporary_password)}"></div></div><div class="form-actions"><button class="primary" data-action="close-modal">完成</button></div>`);
      users();
    }catch(x){toast(x.message)}
  };
}
async function changeRole(id,role){
  try{await api('/users/'+id,{method:'PATCH',body:JSON.stringify({role})});toast('用户权限已更新');users()}
  catch(e){toast('权限修改失败：'+e.message);users()}
}
async function editUser(id,enabled){
  try{await api('/users/'+id,{method:'PATCH',body:JSON.stringify({enabled:!enabled})});toast('账号状态已更新');users()}
  catch(e){toast(e.message)}
}
async function resetPassword(id){
  if(!confirm('确定重置该账号密码吗？重置后其所有登录会话立即失效。'))return;
  try{const r=await api(`/users/${id}/reset-password`,{method:'POST',body:'{}'});modal('密码已重置',`<div class="form-grid"><p class="full">请将临时密码安全交给用户，首次登录必须修改。</p><div class="full"><b>临时密码</b><input readonly value="${esc(r.temporary_password)}"></div></div><div class="form-actions"><button class="primary" data-action="close-modal">完成</button></div>`)}
  catch(e){toast('重置失败：'+e.message)}
}
async function deleteUser(id){
  const reason=prompt('请输入删除原因');if(!reason)return;
  try{await api('/users/'+id,{method:'DELETE',body:JSON.stringify({reason})});toast('用户已删除并停用');users()}
  catch(e){toast('删除失败：'+e.message)}
}
function newTemplate(){
  modal('新建模板',`<form id="new-template-form" class="form-grid" autocomplete="off"><label>模板编号<input name="code" placeholder="例如 HT-SERVICE" required maxlength="50" pattern="[A-Za-z0-9_.\\-]{1,50}"></label><label>模板名称<input name="name" required maxlength="150"></label><label>分类<input name="category" placeholder="采购合同" required maxlength="60"></label><label class="full">说明<textarea name="description" maxlength="2000"></textarea></label><div id="template-form-error" class="error full" role="alert"></div><div class="form-actions full"><button type="button" class="ghost" data-action="close-modal">取消</button><button type="submit" class="primary" id="create-template-submit">创建模板</button></div></form>`);
  const form=$('#new-template-form');
  form.onsubmit=async e=>{
    e.preventDefault();
    const button=$('#create-template-submit'),error=$('#template-form-error');
    if(button.disabled)return;
    button.disabled=true;button.textContent='正在创建…';error.textContent='';
    try{
      await api('/templates',{method:'POST',body:JSON.stringify(Object.fromEntries(new FormData(form)))});
      hideModal();toast('模板已创建，请进入详情创建版本并上传 DOCX 母版');templates();
    }catch(x){error.textContent=x.message;toast('创建失败：'+x.message)}
    finally{button.disabled=false;button.textContent='创建模板'}
  };
}
function changePassword(){
  modal('修改密码',`<form id="change-password-form" class="form-grid"><label class="full">当前密码<input name="old_password" type="password" required></label><label>新密码<input name="new_password" type="password" minlength="6" required></label><label>确认新密码<input name="confirm_password" type="password" minlength="6" required></label><div id="change-password-error" class="error full"></div><div class="form-actions full"><button type="button" class="ghost" data-action="close-modal">取消</button><button class="primary">保存新密码</button></div></form>`);
  $('#change-password-form').onsubmit=async e=>{
    e.preventDefault();
    const d=Object.fromEntries(new FormData(e.currentTarget));
    if(d.new_password!==d.confirm_password){$('#change-password-error').textContent='两次输入的新密码不一致';return}
    try{
      const user=await api('/auth/change-password',{method:'POST',body:JSON.stringify({old_password:d.old_password,new_password:d.new_password})});
      setSession(user);hideModal();toast('密码修改成功');
    }catch(x){$('#change-password-error').textContent=x.message}
  };
}

// ---------- 事件绑定 ----------
document.addEventListener('click',e=>{
  const a=e.target.closest('[data-action]');
  if(a){
    const x=a.dataset.action;
    if(x==='close-modal'){closeModal();return}
    if(!me||me.must_change_password)return;
    if(x==='new-contract')newContract();
    if(x==='refresh-contracts')contracts();
    if(x==='refresh-templates')templates();
    if(x==='new-user')newUser();
    if(x==='new-template')newTemplate();
    if(x==='refresh-audit')auditPage=1,auditLogs();
    if(x==='reset-audit')resetAudit();
    if(x==='refresh-system')systemStatus();
  }
  if(e.target===$('#modal'))closeModal();
  const nav=e.target.closest('a[href^="#"]');
  if(nav){e.preventDefault();show(nav.hash.slice(1))}
});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!$('#modal').hidden)closeModal()});
// 关标签页/刷新前也提醒一下：弹窗里的未保存内容会丢
window.addEventListener('beforeunload',e=>{if(!$('#modal').hidden&&modalDirtyCheck&&modalDirtyCheck()){e.preventDefault();e.returnValue=''}});
window.addEventListener('hashchange',()=>show(location.hash.slice(1)||'dashboard'));

$('#login-form').addEventListener('submit',async e=>{
  e.preventDefault();
  const form=e.currentTarget,button=form.querySelector('button');
  if(button.disabled)return;
  const data=Object.fromEntries(new FormData(form));
  button.disabled=true;button.textContent='正在登录…';$('#login-error').textContent='';
  try{
    const user=await api('/auth/login',{method:'POST',body:JSON.stringify(data)});
    form.reset();setSession(user);
    if(user.must_change_password)$('#password-form input').focus();
    else show('dashboard');
  }catch(err){$('#login-error').textContent=err.message}
  finally{button.disabled=false;button.textContent='登录系统'}
});
$('#password-form').addEventListener('submit',async e=>{
  e.preventDefault();
  const form=e.currentTarget,button=form.querySelector('button');
  if(button.disabled)return;
  const data=Object.fromEntries(new FormData(form));
  $('#password-error').textContent='';
  if(data.new_password!==data.confirm_password){$('#password-error').textContent='两次输入的新密码不一致';return}
  button.disabled=true;button.textContent='正在修改…';
  try{
    const user=await api('/auth/change-password',{method:'POST',body:JSON.stringify({old_password:data.old_password,new_password:data.new_password})});
    form.reset();setSession(user);show('dashboard');toast('密码已修改');
  }catch(err){$('#password-error').textContent=err.message}
  finally{button.disabled=false;button.textContent='修改密码并进入工作台'}
});
$('#logout').onclick=async()=>{
  const button=$('#logout');if(button.disabled)return;button.disabled=true;
  try{await api('/auth/logout',{method:'POST',body:'{}'});setSession(null);$('#login-error').textContent='';$('#login-form input').focus()}
  catch(err){if(err.status===401)setSession(null);else toast('退出失败：'+err.message)}
  finally{button.disabled=false}
};
$('#change-password').onclick=changePassword;
$('#contract-search').oninput=()=>{contractPage=1;contracts()};
$('#contract-status').onchange=()=>{contractPage=1;contracts()};
$('#template-search').oninput=()=>templates();
$('#template-category').onchange=()=>templates();
window.addEventListener('pageshow',()=>{if(!me){const f=$('#login-form [name=password]');if(f)f.value=''}});

init();
