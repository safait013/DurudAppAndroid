/* Run with: node tests/durood-ui.test.cjs
 * Exercises the actual bundled functions with a minimal DOM/Android bridge.
 * Does not emulate Room, MediaPlayer, or browser layout.
 */
function run(html, locales) {
  let checks = 0;
  function assert(ok, message) { if (!ok) throw new Error(message); checks++; }
  for (const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) new Function(match[1]);
  const bn = new Function(html.match(/let daroodData = \[[\s\S]*?\r?\n\];/)[0] + ';return daroodData;')();
  const source = html.slice(html.indexOf('// Audio availability'), html.indexOf('function renderSalamCards('));
  const nodes = {};
  function node() { return {innerHTML:'', textContent:'', hidden:false, style:{}, classList:{add(){},remove(){}}}; }
  ['cards-container','activity-list','activity-empty','activity-status','screen-activity','screen-title','screen-modal','menu-modal'].forEach(id => nodes[id]=node());
  const buttons = [1,2].map(id => ({
    dataset:{duroodAudio:String(id)}, attributes:{}, icon:{textContent:'▶'},
    setAttribute(k,v){this.attributes[k]=v;}, querySelector(){return this.icon;}
  }));
  const doc = { getElementById:id=>nodes[id], querySelectorAll:selector=>selector==='[data-durood-audio]'?buttons:[] };
  let reads=[], loads=0, toggles=[];
  const Android = {
    hasDuroodAudio:id=>id>=1&&id<=15, readDurood:id=>reads.push(id),
    loadActivity:()=>loads++, requestDuroodAudioState(){},
    playDuroodPronunciation:id=>toggles.push(id), stopDuroodPronunciation(){}
  };
  const window = {Android};
  const STR = key=>key, toLocalDigits=String;
  const fmt = (key,value)=>String(value);
  let toasts=[];
  const ui = new Function('window','Android','document','STR','showToast',
    source+';return {renderCards,readDurood,toggleDuroodPanel,playDuroodPronunciation};')(window,Android,doc,STR,s=>toasts.push(s));
  const data = {bn};
  for (const [lang,script] of Object.entries(locales || {})) {
    const w={religiousContentEn:{},religiousContentUr:{}};
    new Function('window',script)(w);
    const records=(lang==='en'?w.religiousContentEn:w.religiousContentUr).darood;
    data[lang]=records.map(d=>({...d,arabic:bn.find(b=>b.num===d.num).arabic}));
  }
  for(const [lang,records] of Object.entries(data)){
    assert(records.length===25,lang+' exactly 25 corrected items');
    const digits={bn:'০১২৩৪৫৬৭৮৯',en:'0123456789',ur:'۰۱۲۳۴۵۶۷۸۹'}[lang];
    records.forEach((d,i)=>{
      const title={bn:'হাদীস',en:'Hadith',ur:'حدیث'}[lang]+' '+String(i+1).replace(/[0-9]/g,c=>digits[+c]);
      assert(d.num===i+1&&d.title===title,lang+' stable ID and localized title');
      assert(d.arabic===bn[i].arabic&&d.ref===bn[i].ref,lang+' invariant Arabic and reference');
    });
  }
  for (const [lang,records] of Object.entries(data)) for (const d of records) {
    ui.renderCards([d]);
    const card=nodes['cards-container'].innerHTML;
    const controls=card.match(/<div class="durood-controls">([\s\S]*?)<\/div>/)[1];
    assert((controls.match(/<button /g)||[]).length===3,lang+' three controls');
    assert(card.includes('class="action-row durood-actions"'),lang+' actions row');
    assert(card.includes('copyText(this,')&&card.includes('shareText(')&&card.includes('readDurood('+d.num+')'),lang+' action routing');
    assert(card.includes('class="uchchar"')===(d.num>15),lang+' text pronunciation fallback');
    assert(card.includes('data-durood-audio=')===(d.num<=15),lang+' bundled audio availability');
    assert(card.includes(d.arabic)&&card.includes(d.ortho||'')&&card.includes(d.ref),lang+' content retained');
    if(d.num>15&&!d.uchchar)assert(controls.includes('disabled'),lang+' missing pronunciation safely disabled');
    if(d.num<=15){
      const handler=[...card.matchAll(/onclick="([^"]+)"/g)].map(m=>m[1]).find(h=>h.startsWith('playDuroodPronunciation('));
      new Function('playDuroodPronunciation',handler)(ui.playDuroodPronunciation);
      assert(toggles.at(-1)===d.num,lang+' audio button routes stable ID');
    }
    const handlers=[...card.matchAll(/onclick="([^"]+)"/g)].map(m=>m[1]);
    let copied,shared;
    new Function('copyText',handlers.find(h=>h.startsWith('copyText(')))((...args)=>copied=args.slice(1));
    new Function('shareText',handlers.find(h=>h.startsWith('shareText(')))((...args)=>shared=args);
    assert(JSON.stringify(copied)===JSON.stringify([d.arabic,d.uchchar||'',d.ortho||'']),lang+' Copy current content');
    assert(JSON.stringify(shared)===JSON.stringify([d.arabic,d.ortho||'',d.title]),lang+' Share current localized content');
  }
  ui.renderCards([bn[9],bn[0]]);
  assert(nodes['cards-container'].innerHTML.includes('readDurood(10)'), 'Filtered Read uses stable ID');
  for(let i=0;i<5;i++)ui.readDurood(1);
  for(let i=0;i<100;i++)ui.readDurood(2);
  assert(reads.filter(id=>id===1).length===5&&reads.filter(id=>id===2).length===100,'Each tap reaches native bridge once');
  assert(!toasts.length,'No optimistic save acknowledgement');
  ui.playDuroodPronunciation(1);ui.playDuroodPronunciation(1);
  assert(toggles.slice(-2).join(',')==='1,1','Play/Stop delegates to native player');
  const played=toggles.length;
  ui.playDuroodPronunciation(16);ui.playDuroodPronunciation(25);
  assert(toggles.length===played,'Missing recordings do not reach native playback');
  window.__duroodAudioState(1,true);
  assert(buttons[0].icon.textContent==='■'&&buttons[1].icon.textContent==='▶','Playing state');
  window.__duroodAudioState(2,true);
  assert(buttons[0].icon.textContent==='▶'&&buttons[1].icon.textContent==='■','Switching state');
  window.__duroodAudioState(0,false);
  assert(buttons.every(b=>b.icon.textContent==='▶'&&b.attributes['aria-pressed']==='false'),'Stop/completion/error reset');
  nodes.panel={hidden:true};
  const control={getAttribute:()=> 'panel',setAttribute(k,v){this[k]=v;}};
  ui.toggleDuroodPanel(control);assert(!nodes.panel.hidden&&control['aria-expanded']==='true','Expand');
  ui.toggleDuroodPanel(control);assert(nodes.panel.hidden&&control['aria-expanded']==='false','Collapse');
  const activity=html.slice(html.indexOf('// ====== MY ACTIVITY ======'),html.indexOf('// ====== SETTINGS CONTROLS ======'));
  new Function('window','Android','document','STR','fmt','toLocalDigits','daroodData','salamData','currentLang','pad2',
    activity+';window.loadActivityData=loadActivityData;')(window,Android,doc,STR,fmt,toLocalDigits,bn,[],()=> 'en',n=>String(n).padStart(2,'0'));
  const records=[{type:'durood',contentId:1,date:'2026-09-07',count:3},{type:'salam',contentId:2,date:'2026-09-07',count:5}];
  window.__activityLoaded(records);
  assert(nodes['activity-list'].innerHTML.includes(bn[0].title)&&nodes['activity-empty'].style.display==='none','Native array regression');
  window.__activityLoaded(JSON.stringify(records));
  assert(nodes['activity-status'].hidden,'JSON string compatibility');
  window.__activityLoaded([]);
  assert(nodes['activity-empty'].style.display==='block','Localized empty state');
  window.__activityLoadFailed();
  assert(nodes['activity-status'].textContent==='activity_load_failed'&&nodes['activity-empty'].style.display==='none','Query errors are not empty results');
  window.__activityLoaded('invalid JSON');
  assert(nodes['activity-status'].textContent==='activity_load_failed','Malformed payload visible error');
  const menu=html.slice(html.indexOf('function closeMenu(){'),html.indexOf('// First-launch setup'));
  const open=new Function('document','STR','stopDuroodPronunciation','loadActivityData','openHijriSettings',menu+';return showScreen;')(doc,STR,()=>{},window.loadActivityData,()=>{});
  open('activity');open('activity');
  assert(loads===2,'Each Menu opening requests fresh DB data');
  window.__activityLoaded([{...records[0],count:4}]);
  assert(nodes['activity-list'].innerHTML.includes('activity-count">4<'),'Fresh saved count renders');
  assert(html.includes("if(_d) recordActivity('durood', _d.num)")&&html.includes("if(_s) recordActivity('salam', _s.num)"),'Tasbih tracking preserved');
  assert(html.includes('grid-template-columns:minmax(0,1.32fr) minmax(0,.9fr) minmax(0,1.12fr)')&&html.includes('min-height:48px'),'Three columns and touch targets');
  return {checks, languages:Object.keys(data)};
}
if(typeof module!=='undefined') module.exports=run;
if(typeof require!=='undefined' && require.main===module){
  const fs=require('fs'),path=require('path'),root=path.join(__dirname,'..');
  console.log(run(fs.readFileSync(path.join(root,'app/src/main/assets/index.html'),'utf8'),Object.fromEntries(['en','ur'].map(lang=>[lang,fs.readFileSync(path.join(root,'app/src/main/assets/data/'+lang+'-darood.js'),'utf8')]))));
}
