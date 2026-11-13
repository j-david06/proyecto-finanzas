// Monetary values remain decimal strings throughout the browser layer.
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const KEY_PATTERN = /^pf_ing_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[A-Za-z0-9_-]{43,128}$/;
const STATES = Object.freeze({RECEIVED:'Recibido',PROCESSING:'Procesando',RETRY:'En reintento',PROCESSED:'Procesado',REVIEW:'Por revisar',DEAD:'No procesado'});

export function normalizeAmount(value) {
  if (typeof value !== 'string') throw new Error('El importe debe ser una cadena decimal.');
  const text = value.trim();
  if (!/^(?:0|[1-9][0-9]{0,15})(?:[.,][0-9]{1,2})?$/.test(text) || !/[1-9]/.test(text)) {
    throw new Error('Escribe un importe positivo, sin separadores de miles y con máximo dos decimales.');
  }
  const [integer, fraction = ''] = text.replace(',', '.').split('.');
  return `${integer}.${fraction.padEnd(2, '0')}`;
}

export function formatAmount(decimal) {
  const normalized = normalizeAmount(decimal);
  const [integer, fraction] = normalized.split('.');
  return `$ ${integer.replace(/\B(?=(\d{3})+(?!\d))/g, '.')},${fraction}`;
}

export function validateConnection(apiKey, accountId) {
  if (!KEY_PATTERN.test(apiKey) || !UUID_PATTERN.test(accountId)) {
    throw new Error('Revisa la API Key y el UUID de la cuenta en .env.shortcuts.');
  }
  return Object.freeze({apiKey, accountId});
}

export function parseCredentials(text) {
  if (text.length > 8192) throw new Error('El archivo de conexión es demasiado grande.');
  const values = new Map();
  for (const line of text.replace(/^\uFEFF/, '').split(/\r?\n/)) {
    const separator = line.indexOf('=');
    if (separator < 0) continue;
    const name = line.slice(0, separator);
    if (!['INGESTION_API_KEY','INGESTION_ACCOUNT_ID'].includes(name)) continue;
    if (values.has(name)) throw new Error('El archivo tiene campos de conexión repetidos.');
    values.set(name, line.slice(separator + 1).trim());
  }
  return validateConnection(values.get('INGESTION_API_KEY') || '', values.get('INGESTION_ACCOUNT_ID') || '');
}

export function createAttempt(fields, accountId, eventId) {
  if (!UUID_PATTERN.test(accountId) || !UUID_PATTERN.test(eventId)) throw new Error('Identificador inválido.');
  if (!['INCOME','EXPENSE'].includes(fields.kind)) throw new Error('Selecciona ingreso o gasto.');
  if (!['APPLE_PAY','SMS','EMAIL'].includes(fields.trigger)) throw new Error('Selecciona un origen válido.');
  const merchant = fields.merchant.trim();
  if (!merchant || merchant.length > 200) throw new Error('Escribe un comercio o concepto de hasta 200 caracteres.');
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(fields.date) || !Number.isFinite(Date.parse(`${fields.date}:00-05:00`))) {
    throw new Error('Indica una fecha y hora válidas en Bogotá.');
  }
  const amount = normalizeAmount(fields.amount);
  const body = JSON.stringify({schema_version:1,trigger:fields.trigger,format:'NORMALIZED',account_id:accountId,
    movement:{kind:fields.kind,amount,currency:'COP',occurred_at:`${fields.date}:00-05:00`,merchant}});
  return Object.freeze({eventId,body,accountId,amount,merchant,kind:fields.kind,date:fields.date});
}

export function validReceipt(receipt) {
  return Boolean(receipt && UUID_PATTERN.test(receipt.ingestion_id) && Object.hasOwn(STATES, receipt.status)
    && (receipt.transaction_id == null || UUID_PATTERN.test(receipt.transaction_id))
    && (receipt.status !== 'PROCESSED' || UUID_PATTERN.test(receipt.transaction_id)));
}
export function stateLabel(state) { return STATES[state] || 'Sin confirmar'; }
export function pendingState(state) { return ['RECEIVED','PROCESSING','RETRY'].includes(state); }
export function stateClass(state) { return state === 'PROCESSED' ? 'done' : ['REVIEW','DEAD'].includes(state) ? 'warning' : ''; }

export function errorMessage(status, code) {
  if (code === 'HTTPS_REQUIRED') return 'Este backend exige HTTPS. Para probar solo en localhost, inicia con INGESTION_REQUIRE_HTTPS=false.';
  if (status === 401) return 'La API Key no está activa o no es válida. Revisa la conexión.';
  if (status === 403) return 'La cuenta no está autorizada o está inactiva. Revisa el UUID de la cuenta.';
  if (status === 404) return 'La ingesta no está disponible para esta conexión.';
  if (status === 409) return 'La clave de idempotencia ya está asociada a otro contenido. Este intento no se sobrescribió.';
  if (status === 413) return 'El evento supera el tamaño permitido.';
  if (status === 422) return 'La API rechazó los datos del movimiento. Revisa importe, fecha y moneda de la cuenta.';
  if (status === 429) return 'Hay demasiadas solicitudes. Espera antes de repetir.';
  if (status >= 500) return 'El servidor no pudo confirmar el evento. Repite el mismo intento cuando esté disponible.';
  return 'No se pudo completar la solicitud. Revisa la configuración y los datos del evento.';
}
