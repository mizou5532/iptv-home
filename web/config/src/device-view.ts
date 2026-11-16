import type { Db, Device } from './flow';
import { DEVICE_ICONS, deviceIcon, saveDeviceSettings } from './devices';
import { icon, escapeHtml as esc } from './icons';
interface Options {db:Db;householdId:string;devices:Device[];onRemove:(device:Device)=>void}
export function mountDevices(root:HTMLElement, options:Options):void {
 let editing:string|null=null;
 const paint=()=>{
  root.innerHTML=options.devices.length?options.devices.map(d=>`<div class="device-item"><div class="device-summary">${icon(deviceIcon(d.icon))}<span><strong>${esc(d.name)}</strong>${d.defaultName?`<small>Modèle : ${esc(d.defaultName)}</small>`:''}</span><button class="small secondary" type="button" data-edit="${esc(d.userId)}" aria-label="Modifier ${esc(d.name)}">Modifier</button></div>
  ${editing===d.userId?`<form class="device-editor"><label for="device-name">Nom de la télé</label><input id="device-name" name="name" value="${esc(d.name)}" maxlength="40" required autocomplete="off"><fieldset><legend>Pictogramme</legend><div class="device-icon-picker">${DEVICE_ICONS.map(i=>`<button type="button" data-icon="${i.id}" aria-pressed="${deviceIcon(d.icon)===i.id}">${icon(i.id)}<span>${i.label}</span></button>`).join('')}</div></fieldset><div class="tools"><button class="small" type="submit">Enregistrer</button><button class="small secondary" type="button" data-cancel>Annuler</button></div><p class="msg" role="status" aria-live="polite"></p></form>`:`<button class="link device-remove" type="button" data-remove="${esc(d.userId)}">Retirer cet appareil</button>`}</div>`).join(''):'<p>Aucun appareil pour le moment.</p>';
  root.querySelectorAll<HTMLButtonElement>('[data-edit]').forEach(b=>b.onclick=()=>{editing=b.dataset.edit!;paint();root.querySelector<HTMLInputElement>('input')!.focus();});
  root.querySelectorAll<HTMLButtonElement>('[data-remove]').forEach(b=>b.onclick=()=>options.onRemove(options.devices.find(d=>d.userId===b.dataset.remove)!));
  const form=root.querySelector<HTMLFormElement>('form');if(!form)return;
  const device=options.devices.find(d=>d.userId===editing)!;
  let chosen=deviceIcon(device.icon);
  const cancel=form.querySelector<HTMLButtonElement>('[data-cancel]')!;
  cancel.onclick=()=>{editing=null;paint();};
  const choices=[...form.querySelectorAll<HTMLButtonElement>('[data-icon]')];
  choices.forEach(b=>b.onclick=()=>{chosen=deviceIcon(b.dataset.icon);choices.forEach(c=>c.setAttribute('aria-pressed',String(c===b)));});
  form.onsubmit=async e=>{
   e.preventDefault();const input=form.querySelector<HTMLInputElement>('input')!;const msg=form.querySelector<HTMLElement>('[role="status"]')!;
   const name=input.value;const selected=chosen;const buttons=[...root.querySelectorAll<HTMLButtonElement>('button')];buttons.forEach(b=>b.disabled=true);input.disabled=true;msg.textContent='Enregistrement…';
   try {await saveDeviceSettings(options.db,options.householdId,device,name,selected);device.name=name.trim();device.icon=selected;if(root.isConnected){editing=null;paint();}}
   catch(error){msg.textContent=(error as Error).message;msg.className='msg error';}
   finally{buttons.forEach(b=>b.disabled=false);input.disabled=false;}
  };
 };
 paint();
}
