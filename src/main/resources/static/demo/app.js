import {createAttempt,formatAmount,parseCredentials,validateConnection,validReceipt,stateLabel,pendingState,stateClass,errorMessage} from './model.mjs';

const $ = id => document.getElementById(id);
const state = {connection:null,attempt:null,busy:false,unknown:false,kind:'EXPENSE',rows:[],accepted:0,lastReceipt:null,controller:null};

function message(id, text, tone = '') {
  $(id).textContent = text;
  $(id).className = `inline-message ${tone}`;
}

function nowInBogota() {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-GB',{timeZone:'America/Bogota',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'})
    .formatToParts(new Date()).map(part=>[part.type,part.value]));
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

function updateControls() {
  $('movement-fields').disabled = state.busy || Boolean(state.attempt);
  $('send').hidden = Boolean(state.attempt);
  $('send').disabled = state.busy;
  $('new-event').hidden = !state.attempt;
  $('new-event').disabled = state.busy;
  $('repeat').disabled = state.busy || !state.attempt || !state.connection;
  $('disconnect').disabled = state.busy || !state.connection;
  $('connection-form').querySelector('button[type=submit]').disabled = state.busy;
  $('credential-file').disabled = state.busy;
  $('import-file').disabled = state.busy;
  $('new-event').querySelector('span').textContent = state.busy ? 'Esperando respuesta…' : 'Nuevo evento';
  $('movement-form').setAttribute('aria-busy',String(state.busy));
  $('connection-state').textContent = state.connection ? 'Lista para enviar' : 'Sin configurar';
  $('connection-dot').classList.toggle('ready',Boolean(state.connection));
  document.querySelectorAll('.query-button').forEach(button=>{button.disabled=state.busy;});
}

function resetAttempt() {
  state.attempt = null;
  state.unknown = false;
  state.lastReceipt = null;
  $('receipt-details').hidden = true;
  $('http-status').textContent = 'Sin envío';
  $('http-status').className = 'http-status';
  $('response-description').textContent = 'La respuesta de tu API aparecerá aquí.';
  message('form-message','');
  updateControls();
}

function showReceipt(receipt) {
  $('receipt-state').textContent = `${stateLabel(receipt.status)} · ${receipt.status}`;
  $('receipt-id').textContent = receipt.ingestion_id;
  $('transaction-id').textContent = receipt.transaction_id || 'Aún sin registrar';
  $('processing-result').textContent = processingMessage(receipt);
  $('receipt-details').hidden = false;
}

function processingMessage(receipt) {
  if (receipt.status === 'PROCESSED') return 'Transacción registrada en PostgreSQL.';
  if (receipt.status === 'REVIEW') return receipt.error_code === 'PARSER_UNAVAILABLE'
    ? 'Este mensaje necesita un parser; requiere revisión.' : 'Requiere revisión. No se registró una transacción nueva.';
  if (receipt.status === 'DEAD') return 'Se agotaron los intentos. Requiere intervención.';
  if (receipt.status === 'RETRY') return 'El worker volverá a intentar el procesamiento.';
  return 'Pendiente de procesamiento. Consulta su estado en unos segundos.';
}

function clearConnection() {
  state.controller?.abort();
  state.connection = null;
  state.rows = [];
  state.accepted = 0;
  $('api-key').value = '';
  $('account-id').value = '';
  $('credential-file').value = '';
  resetAttempt();
  renderActivity();
  message('connection-message','Conexión eliminada de esta pestaña.');
  $('connection-details').open = true;
}

function useConnection(connection) {
  if (state.busy) return;
  if (state.connection && (state.connection.apiKey !== connection.apiKey || state.connection.accountId !== connection.accountId)) {
    if (state.unknown && !window.confirm('El último envío no está confirmado. Cambiar de conexión borra ese intento de la pantalla. ¿Continuar?')) return;
    state.rows = [];
    state.accepted = 0;
    resetAttempt();
    renderActivity();
  }
  state.connection = connection;
  $('api-key').value = '';
  $('account-id').value = connection.accountId;
  message('connection-message','Configuración lista. La API verificará tus permisos al enviar.','success');
  $('connection-details').open = false;
  updateControls();
  $('amount').focus();
}

$('connection-form').addEventListener('submit',event=>{
  event.preventDefault();
  if (state.busy) return;
  try { useConnection(validateConnection($('api-key').value.trim(),$('account-id').value.trim())); }
  catch (error) { message('connection-message',error.message,'error'); }
});

$('credential-file').addEventListener('change',async event=>{
  const file = event.target.files?.[0];
  if (!file || state.busy) return;
  try {
    if (file.size > 8192) throw new Error('El archivo de conexión es demasiado grande.');
    useConnection(parseCredentials(await file.text()));
  } catch (error) { message('connection-message',error.message,'error'); }
  finally { event.target.value = ''; }
});
$('import-file').addEventListener('click',()=>$('credential-file').click());

$('disconnect').addEventListener('click',()=>{
  if (state.unknown && !window.confirm('El último envío no está confirmado. Desconectar borra el intento de esta pantalla. ¿Continuar?')) return;
  clearConnection();
});

document.querySelectorAll('.kind-button').forEach(button=>button.addEventListener('click',()=>{
  if (state.busy || state.attempt) return;
  state.kind = button.dataset.kind;
  document.querySelectorAll('.kind-button').forEach(item=>{
    const selected = item === button;
    item.classList.toggle('selected',selected);
    item.setAttribute('aria-pressed',String(selected));
  });
}));

async function request(path, method = 'GET', attempt = null) {
  if (!state.connection) throw new Error('Configura la conexión antes de continuar.');
  const controller = new AbortController();
  state.controller = controller;
  const timeout = setTimeout(()=>controller.abort(),12000);
  try {
    const headers = {'X-API-Key':state.connection.apiKey};
    if (attempt) { headers['Content-Type']='application/json'; headers['Idempotency-Key']=attempt.eventId; }
    const response = await fetch(path,{method,headers,body:attempt?.body,signal:controller.signal,
      credentials:'omit',mode:'same-origin',redirect:'error',cache:'no-store',referrerPolicy:'no-referrer'});
    let data;
    try { data = await response.json(); } catch { data = null; }
    return {status:response.status,ok:response.ok,data};
  } finally { clearTimeout(timeout); state.controller = null; }
}

async function submitAttempt() {
  if (!state.attempt || !state.connection || state.busy) return;
  state.busy = true;
  updateControls();
  message('form-message','Enviando evento…');
  $('http-status').textContent = 'Enviando…';
  $('http-status').className = 'http-status';
  try {
    const result = await request('/api/v1/transactions/ingest','POST',state.attempt);
    $('http-status').textContent = `HTTP ${result.status}`;
    if (!result.ok) {
      state.unknown = result.status >= 500;
      const explanation = errorMessage(result.status,result.data?.code);
      $('http-status').className = 'http-status bad';
      $('response-description').textContent = explanation;
      message('form-message',explanation,'error');
      return;
    }
    if (![200,202].includes(result.status) || !validReceipt(result.data)) throw new Error('Unconfirmed response');
    const replay = state.lastReceipt !== null;
    if (replay && state.lastReceipt !== result.data.ingestion_id) throw new Error('Unconfirmed receipt identity');
    state.unknown = false;
    state.lastReceipt = result.data.ingestion_id;
    const existing = state.rows.find(row=>row.id === result.data.ingestion_id);
    if (existing) Object.assign(existing,{status:result.data.status,transaction_id:result.data.transaction_id,error_code:result.data.error_code});
    else {
      state.accepted += 1;
      state.rows.unshift({...state.attempt,id:result.data.ingestion_id,status:result.data.status,
        transaction_id:result.data.transaction_id,error_code:result.data.error_code});
      state.rows = state.rows.slice(0,20);
    }
    $('http-status').className = 'http-status good';
    $('response-description').textContent = replay ? 'Repetición aceptada. Se conservó el ID de la ingesta.' : 'Evento aceptado y guardado por tu API.';
    showReceipt(result.data);
    message('form-message',replay ? 'Mismo evento, mismo ID. Sin una nueva ingesta.' : 'Envío confirmado. Puedes consultar su estado en la actividad.','success');
    renderActivity();
  } catch {
    state.unknown = true;
    $('http-status').textContent = 'Sin confirmar';
    $('http-status').className = 'http-status bad';
    const explanation = 'No pudimos confirmar el envío. Comprueba que la API esté activa y repite el mismo intento; pudo haber llegado al servidor.';
    $('response-description').textContent = explanation;
    message('form-message',explanation,'error');
  } finally { state.busy = false; updateControls(); }
}

$('movement-form').addEventListener('submit',async event=>{
  event.preventDefault();
  if (state.busy || state.attempt) return;
  if (!state.connection) {
    $('connection-details').open = true;
    message('form-message','Primero configura tu API Key y la cuenta autorizada.','error');
    $('api-key').focus();
    return;
  }
  try {
    if (!window.isSecureContext || !crypto.randomUUID) throw new Error('Abre el panel en localhost o mediante HTTPS.');
    state.attempt = createAttempt({kind:state.kind,amount:$('amount').value,merchant:$('merchant').value,date:$('occurred-at').value,trigger:$('trigger').value},
      state.connection.accountId,crypto.randomUUID());
    await submitAttempt();
  } catch (error) { message('form-message',error.message,'error'); }
});

$('repeat').addEventListener('click',submitAttempt);
$('new-event').addEventListener('click',()=>{
  if (state.busy) return;
  if (state.unknown && !window.confirm('El último envío no está confirmado. Crear otro evento usa una clave nueva y podría duplicarlo. ¿Continuar?')) return;
  resetAttempt();
  $('amount').value = '';
  $('merchant').value = '';
  $('occurred-at').value = nowInBogota();
  $('amount').focus();
});

async function poll(row, button, feedback) {
  if (state.busy || !state.connection) return;
  state.busy = true;
  updateControls();
  feedback.textContent = 'Consultando…';
  try {
    const result = await request(`/api/v1/ingestions/${row.id}`);
    if (!result.ok) throw new Error(errorMessage(result.status,result.data?.code));
    if (!validReceipt(result.data) || result.data.ingestion_id !== row.id) throw new Error('La respuesta de estado no pudo validarse.');
    Object.assign(row,{status:result.data.status,transaction_id:result.data.transaction_id,error_code:result.data.error_code});
    if (state.lastReceipt === row.id) showReceipt(result.data);
    renderActivity();
  } catch (error) {
    feedback.textContent = error instanceof TypeError || error.name === 'AbortError' ? 'No se pudo conectar. Intenta consultar de nuevo.' : error.message;
  } finally { state.busy = false; updateControls(); }
}

function renderActivity() {
  $('accepted-count').textContent = String(state.accepted);
  $('pending-count').textContent = String(state.rows.filter(row=>pendingState(row.status)).length);
  $('activity-count').textContent = `${state.rows.length} ${state.rows.length === 1 ? 'evento' : 'eventos'}`;
  $('empty-state').hidden = state.rows.length > 0;
  $('activity-list').replaceChildren();
  for (const row of state.rows) {
    const item = $('event-template').content.cloneNode(true);
    const icon = item.querySelector('.event-icon');
    icon.textContent = row.kind === 'INCOME' ? '↙' : '↗';
    icon.classList.toggle('income',row.kind === 'INCOME');
    item.querySelector('.event-merchant').textContent = row.merchant;
    const [date,time] = row.date.split('T');
    const [year,month,day] = date.split('-');
    item.querySelector('.event-time').textContent = `${day}/${month}/${year} · ${time} · Bogotá`;
    item.querySelector('.event-amount').textContent = `${row.kind === 'INCOME' ? '+' : '−'} ${formatAmount(row.amount)}`;
    const badge = item.querySelector('.state-badge');
    badge.textContent = stateLabel(row.status);
    badge.className = `state-badge ${stateClass(row.status)}`;
    const button = item.querySelector('.query-button');
    const feedback = item.querySelector('.event-query-message');
    feedback.textContent = ['PROCESSED','REVIEW','DEAD','RETRY'].includes(row.status)
      ? processingMessage(row) : '';
    button.setAttribute('aria-label',`Consultar estado de ${row.merchant}`);
    button.disabled = state.busy;
    button.addEventListener('click',()=>poll(row,button,feedback));
    $('activity-list').append(item);
  }
}

$('server-address').textContent = window.location.origin;
$('occurred-at').value = nowInBogota();
window.addEventListener('pagehide',clearConnection);
updateControls();
