/* Actual bundled Settings functions, with a minimal DOM/native bridge. */
function run(html) {
  let checks=0;
  function check(ok, message) { if(!ok) throw new Error(message); checks++; }
  for(const script of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) new Function(script[1]);
  const nodes={};
  const document={getElementById(id){return nodes[id]||(nodes[id]={innerHTML:'',classList:{toggle(){}}});}};
  let saved, picked;
  let fridayEnabled=true;
  const Android={saveNotifications(json){saved=JSON.parse(json);},openTimePicker(...args){picked=args;},
    getReminderCapabilities(){return JSON.stringify({notifications:true,exact:false,fullScreen:false});},
    getFridayReminderConfig(){return JSON.stringify({enabled:fridayEnabled,scheduled:false});},
    setFridayReminderEnabled(enabled){fridayEnabled=enabled;}};
  const window={Android};
  const source=html.slice(html.indexOf('let NOTIF_CONFIG ='),html.indexOf('// Close modals on overlay click'));
  const ui=new Function('window','Android','document','STR',source+';return {refreshReminderAccess,onFridayToggle,initSettings,renderNotifDays,addTime,removeTime,pickTime,setReminderMode,setConfig(c){NOTIF_CONFIG=c;}};')(window,Android,document,k=>k);
  ui.setConfig({enabled:true,days:{2:[{h:20,m:0},{h:21,m:30,alertMode:'VIBRATE'},{h:22,m:15,alertMode:'SILENT'}]}});
  ui.renderNotifDays();
  check(nodes['notif-days-list'].innerHTML.includes('value="RING" selected'),'legacy reminder shows Ring');
  check(nodes['notif-days-list'].innerHTML.includes('value="SILENT" selected'),'saved Silent renders');
  ui.setReminderMode(2,0,'SILENT');
  check(saved.days[2][0].alertMode==='SILENT','selected mode persisted through native bridge');
  window.__notificationTimePicked(2,0,19,45);
  check(saved.days[2][0].h===19&&saved.days[2][0].m===45&&saved.days[2][0].alertMode==='SILENT','editing time preserves mode');
  check(saved.days[2][1].alertMode==='VIBRATE','editing another reminder preserves Vibrate');
  ui.pickTime(2,1);
  check(picked.join(',')==='2,1,21,30','day/index/time picker mapping preserved');
  ui.removeTime(2,0);
  check(saved.days[2].length===2&&saved.days[2][0].alertMode==='VIBRATE','delete preserves shifted reminder modes');
  for(let i=0;i<25;i++)ui.addTime(6);
  check(saved.days[6].length===20,'existing per-day limit preserved');
  ui.setReminderMode(2,0,'invalid');
  check(saved.days[2][0].alertMode==='VIBRATE','invalid mode ignored');
  const savedBefore=JSON.stringify(saved);
  ui.refreshReminderAccess();
  check(nodes['reminder-access-setup'].innerHTML===nodes['reminder-access-settings'].innerHTML,'setup and settings share capability state');
  check(nodes['reminder-access-setup'].innerHTML.includes("openReminderAccess('exact')"),'missing exact access offers system settings');
  check(!nodes['reminder-access-setup'].innerHTML.includes("openReminderAccess('notifications')"),'granted access does not prompt again');
  check(nodes['set-friday-enable'].checked&&nodes['friday-reminder-status'].textContent==='friday_reminder_waiting','default Friday state and deferred status render');
  ui.onFridayToggle(false);ui.refreshReminderAccess();
  check(!nodes['set-friday-enable'].checked,'disabled Friday stays disabled after UI refresh');
  check(JSON.stringify(saved)===savedBefore,'Friday toggle does not overwrite user schedules');
  return {checks};
}
if(typeof module!=='undefined') module.exports=run;
if(typeof require!=='undefined'&&require.main===module)console.log(run(require('fs').readFileSync(require('path').join(__dirname,'../app/src/main/assets/index.html'),'utf8')));
