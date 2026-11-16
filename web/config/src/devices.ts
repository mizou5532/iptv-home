import type { Db, Device } from './flow';
export const DEVICE_ICONS = [
 {id:'tv',label:'Télé'}, {id:'sofa',label:'Salon'}, {id:'bed',label:'Chambre'}, {id:'desk',label:'Bureau'}, {id:'other',label:'Autre'},
] as const;
export type DeviceIcon = typeof DEVICE_ICONS[number]['id'];
export function deviceIcon(value: unknown): DeviceIcon { return DEVICE_ICONS.some(x=>x.id===value) ? value as DeviceIcon : 'tv'; }
export async function saveDeviceSettings(db: Db, household: string, device: Device, name: string, icon: string): Promise<void> {
 const trimmed=name.trim();
 if (!trimmed || Array.from(trimmed).length>40) throw new Error('Choisis un nom de 1 à 40 caractères.');
 if (!DEVICE_ICONS.some(x=>x.id===icon)) throw new Error('Choisis un pictogramme.');
 const r=await db.rpc('set_device_settings',{h:household,device:device.userId,name:trimmed,icon});
 if(r.error) throw new Error('Impossible d’enregistrer cet appareil. Réessaie.');
}
