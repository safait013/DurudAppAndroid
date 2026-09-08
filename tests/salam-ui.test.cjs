/* Run: node tests/salam-ui.test.cjs. Tests bundled JS with a fake DOM/bridge, not device playback. */
function run(html, locales) {
  let checks=0;
  const assert=(ok,message)=>{if(!ok)throw Error(message);checks++;};
  for(const s of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g))new Function(s[1]);
  const bn=new Function(html.match(/let salamData = \[[\s\S]*?\r?\n\];/)[0]+';return salamData;')();
  const records={bn};
  for(const [lang,script] of Object.entries(locales)){
    const window={religiousContentEn:{},religiousContentUr:{}};
    new Function('window',script)(window);
    records[lang]=(lang==='en'?window.religiousContentEn:window.religiousContentUr).salam.map(d=>({...d,arabic:bn.find(b=>b.num===d.num).arabic}));
  }
  const nodes={};
  for(const id of ['cards-container','salam-cards-container','activity-list','activity-empty','activity-status'])
    nodes[id]={innerHTML:'',style:{},hidden:false};
  const buttons=[1,2,3,4].map(id=>({dataset:{salamAudio:String(id)},attrs:{},icon:{textContent:'▶'},
    setAttribute(k,v){this.attrs[k]=v;},querySelector(){return this.icon;}}));
  const document={getElementById:id=>nodes[id],querySelectorAll:q=>q==='[data-salam-audio]'?buttons:[]};
  const reads=[],plays=[],copies=[],shares=[],toasts=[];
  let snapshots=0;
  const Android={hasSalamAudio:id=>id>=1&&id<=4,hasDuroodAudio:()=>true,
    playSalamPronunciation:id=>plays.push(id),readSalam:id=>reads.push(id),
    requestDuroodAudioState:()=>snapshots++};
  const window={Android},STR=k=>k;
  const source=html.slice(html.indexOf('// Audio availability'),html.indexOf('function renderIntro()'));
  const ui=new Function('window','Android','document','STR','showToast',source+';return {renderSalamCards,readSalam,playSalamPronunciation,toggleDuroodPanel};')(window,Android,document,STR,s=>toasts.push(s));
  function click(card,needle){
    const handler=[...card.matchAll(/onclick="([^"]+)"/g)].map(m=>m[1]).find(s=>s.includes(needle));
    assert(!!handler,'Handler present: '+needle);
    new Function('copyText','shareText','readSalam','playSalamPronunciation','toggleDuroodPanel',handler)(
      (...args)=>copies.push(args),(...args)=>shares.push(args),ui.readSalam,ui.playSalamPronunciation,ui.toggleDuroodPanel);
  }
  for(const [lang,data] of Object.entries(records))for(const d of data){
    ui.renderSalamCards([d]);
    const card=nodes['salam-cards-container'].innerHTML;
    assert((card.match(/<button /g)||[]).length===6,lang+' six actions');
    assert(card.includes('class="durood-controls"')&&card.includes('class="action-row durood-actions"'),lang+' shared styles');
    assert(card.includes('class="btn btn-read"')&&card.includes('{{btn_read}}'),lang+' Read style/label');
    assert(card.includes('class="uchchar"')===(d.num>4),lang+' audio/text fallback');
    assert(card.includes(d.arabic)&&card.includes(d.ortho||'')&&card.includes(d.ref)&&card.includes(d.title),lang+' content retained');
    for(const name of ['meaning','reference'])assert(card.includes('aria-controls="salam-'+name+'-'+d.num+'"'),lang+' unique panel');
    click(card,'copyText(');assert(copies.at(-1).slice(1).join('|')===[d.arabic,d.uchchar||'',d.ortho||''].join('|'),lang+' original Copy content');
    click(card,'shareText(');assert(shares.at(-1).join('|')===[d.arabic,d.ortho||'',d.title].join('|'),lang+' original Share content');
    if(d.num<=4){click(card,'playSalamPronunciation(');assert(plays.at(-1)===d.num,lang+' stable audio ID');}
    else assert(!card.includes('data-salam-audio='),lang+' no missing audio access');
  }
  ui.renderSalamCards([bn[3],bn[0]]);
  const filtered=nodes['salam-cards-container'].innerHTML;
  const before=plays.length;
  for(let i=0;i<5;i++)click(filtered,'readSalam(4)');
  for(let i=0;i<100;i++)ui.readSalam(1);
  assert(reads.filter(id=>id===4).length===5&&reads.filter(id=>id===1).length===100,'Every Read tap routed once with stable ID');
  assert(plays.length===before,'Read never starts audio');
  assert(!toasts.length,'No optimistic save acknowledgement');
  ui.playSalamPronunciation(1);ui.playSalamPronunciation(1);
  assert(plays.slice(-2).join(',')==='1,1','Toggle delegated to native state');
  window.__salamAudioState(1,true);
  assert(buttons[0].attrs['aria-pressed']==='true'&&buttons[0].icon.textContent==='■','Playing state');
  window.__salamAudioState(2,true);
  assert(buttons[0].icon.textContent==='▶'&&buttons[1].icon.textContent==='■','Switching state');
  window.__salamAudioState(0,false);
  assert(buttons.every(b=>b.icon.textContent==='▶'&&b.attrs['aria-pressed']==='false'),'Stop/completion/error state reset');
  for(const name of ['pronunciation','meaning','reference']){
    nodes.panel={hidden:true};
    const control={getAttribute:()=> 'panel',setAttribute(k,v){this[k]=v;}};
    ui.toggleDuroodPanel(control);assert(!nodes.panel.hidden&&control['aria-expanded']==='true',name+' expand');
    ui.toggleDuroodPanel(control);assert(nodes.panel.hidden&&control['aria-expanded']==='false',name+' collapse');
  }
  const activity=html.slice(html.indexOf('// ====== MY ACTIVITY ======'),html.indexOf('// ====== SETTINGS CONTROLS ======'));
  new Function('window','Android','document','STR','fmt','toLocalDigits','daroodData','salamData','currentLang','pad2',activity)(
    window,Android,document,STR,(k,v)=>String(v),String,[],bn,()=> 'en',n=>String(n).padStart(2,'0'));
  window.__activityLoaded([{type:'salam',contentId:4,date:'2026-09-08',count:5}]);
  assert(nodes['activity-list'].innerHTML.includes(bn[3].title)&&nodes['activity-list'].innerHTML.includes('activity-count">5<'),'Persisted Salam ID and count displayed');
  window.__activityLoaded([{type:'salam',contentId:4,date:'2026-09-08',count:6}]);
  assert(nodes['activity-list'].innerHTML.includes('activity-count">6<'),'Fresh query refreshes displayed count');
  assert(snapshots>0,'Rerender requests shared player state');
  return {checks,languages:Object.keys(records)};
}
if(typeof module!=='undefined')module.exports=run;
if(typeof require!=='undefined'&&require.main===module){
  const fs=require('fs'),path=require('path'),root=path.join(__dirname,'..');
  console.log(run(fs.readFileSync(path.join(root,'app/src/main/assets/index.html'),'utf8'),
    Object.fromEntries(['en','ur'].map(lang=>[lang,fs.readFileSync(path.join(root,'app/src/main/assets/data/'+lang+'-salam.js'),'utf8')]))));
}
