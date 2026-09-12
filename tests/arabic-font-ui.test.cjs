/* Bundled JS with DOM/native doubles; actual font shaping needs a browser/device. */
async function run(html) {
  let checks=0;
  const assert=(ok,message)=>{if(!ok)throw Error(message);checks++;};
  const list=html.match(/const FONTS = \[[\s\S]*?\r?\n\];/)[0];
  const fonts=new Function(list+';return FONTS;')();
  assert(fonts.map(f=>f.code).join(',')==='amiri,scheherazade,lateef,notonaskh,indopak','All existing fonts plus IndoPak');
  const end=html.indexOf('\n}',html.indexOf('function onFontSizeChange('))+2;
  const fontSource=html.slice(html.indexOf('function onFontChange('),end);
  const style={},saved=[],reports=[],pending=[],nodes={};
  const document={documentElement:{style:{setProperty(k,v){style[k]=v;}}},
    getElementById(id){return nodes[id]||(nodes[id]={});},
    fonts:{load(){return new Promise((resolve,reject)=>pending.push({resolve,reject}));}}};
  const Android={setArabicFont:v=>saved.push(v),setArabicFontSize:v=>saved.push(v),reportArabicFontLoad:(...v)=>reports.push(v)};
  const window={Android,APP_SETTINGS:{arabic_font:'amiri',font_scale:1}};
  const ui=new Function('window','document','Android',list+fontSource+';return {onFontChange,applyArabicFont,onFontSizeInput,onFontSizeChange};')(window,document,Android);
  for(const font of fonts){
    ui.onFontChange(font.code);
    assert(style['--arabic-font'].includes(font.family),'Family applied: '+font.code);
    assert(saved.at(-1)===font.code&&window.APP_SETTINGS.arabic_font===font.code,'Saved selection: '+font.code);
    pending.shift().resolve([{}]);await Promise.resolve();
    assert(reports.at(-1)[1]===true,'Successful load logged');
  }
  for(const size of [16,24,32]){
    ui.onFontSizeInput(String(size));ui.onFontSizeChange(String(size));
    assert(style['--arabic-font-size']===size+'px'&&saved.at(-1)===size,'Shared size: '+size);
  }
  ui.applyArabicFont('indopak');pending.shift().reject(Error('missing or corrupt font'));await Promise.resolve();
  assert(style['--arabic-font']==="'AmiriLocal'",'Failed font falls back to Amiri');
  assert(window.APP_SETTINGS.arabic_font==='indopak','Failure preserves saved preference');
  assert(reports.at(-1).join(',')==='indopak,false','Failure logged');
  ui.applyArabicFont('indopak');pending.shift().resolve([]);await Promise.resolve();
  assert(style['--arabic-font']==="'AmiriLocal'",'Empty font load also falls back');
  ui.onFontChange('indopak');ui.onFontChange('lateef');
  pending.shift().reject(Error('late failure'));await Promise.resolve();
  assert(style['--arabic-font'].includes('LateefLocal'),'Old load cannot override new selection');
  pending.shift().resolve([{}]);await Promise.resolve();
  const before=saved.length;ui.applyArabicFont(window.APP_SETTINGS.arabic_font);
  pending.shift().resolve([{}]);await Promise.resolve();
  assert(saved.length===before,'Startup application does not rewrite preference');
  const setup=html.slice(html.indexOf('// First-launch setup'),html.indexOf('// Crash-report consent'));
  const settings=html.slice(html.indexOf('function initSettings()'),html.indexOf('function onThemeChange('));
  assert(setup.includes('FONTS.map')&&settings.includes('FONTS.map'),'Settings and setup share the list');
  assert(html.includes("font-family:var(--arabic-font),serif"),'Existing Arabic selectors keep global font');
  return {checks};
}
if(typeof module!=='undefined')module.exports=run;
if(typeof require!=='undefined'&&require.main===module){
  run(require('fs').readFileSync(require('path').join(__dirname,'../app/src/main/assets/index.html'),'utf8')).then(console.log).catch(e=>{console.error(e);process.exitCode=1;});
}
