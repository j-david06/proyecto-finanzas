import test from 'node:test';
import assert from 'node:assert/strict';
import {createAttempt,formatAmount,normalizeAmount,parseCredentials,validReceipt,stateLabel} from '../../main/resources/static/demo/model.mjs';

const account = 'b61522e7-521a-4565-a857-62fa7c1c0c80';
const eventId = '803c6151-751e-4ec1-9428-d4ae3adf7257';
const key = `pf_ing_${account}.${'a'.repeat(43)}`;

test('preserves exact cents and the numeric(18,2) maximum as strings',()=>{
  assert.equal(normalizeAmount('45900,5'),'45900.50');
  assert.equal(normalizeAmount('0.30'),'0.30');
  assert.equal(normalizeAmount('9999999999999999.99'),'9999999999999999.99');
  assert.equal(formatAmount('9999999999999999.99'),'$ 9.999.999.999.999.999,99');
  assert.throws(()=>normalizeAmount(1));
});

for (const amount of ['0','0,00','-1','1e3','45.900,00','01','0.001','1,234','Infinity','NaN','10000000000000000']) {
  test(`rejects unsafe or ambiguous amount ${amount}`,()=>assert.throws(()=>normalizeAmount(amount)));
}

test('freezes the bytes and identity of an attempt independently of subsequent form edits',()=>{
  const fields = {kind:'EXPENSE',amount:'45900,00',merchant:'Mercado',date:'2026-11-13T14:30',trigger:'APPLE_PAY'};
  const attempt = createAttempt(fields,account,eventId);
  fields.amount = '100';
  fields.merchant = 'Otro';
  assert.equal(attempt.eventId,eventId);
  assert.equal(JSON.parse(attempt.body).movement.amount,'45900.00');
  assert.equal(JSON.parse(attempt.body).movement.merchant,'Mercado');
  assert.equal(JSON.parse(attempt.body).movement.occurred_at,'2026-11-13T14:30:00-05:00');
  assert.ok(Object.isFrozen(attempt));
  assert.throws(()=>{attempt.body='different';});
  assert.ok(!attempt.body.includes('pf_ing_'));
});

test('reads credentials as data without executing shell content and rejects duplicates',()=>{
  const parsed = parseCredentials(`INGESTION_API_KEY=${key}\r\nINGESTION_ACCOUNT_ID=${account}\r\nOTHER=$(whoami)\n`);
  assert.equal(parsed.apiKey,key);
  assert.equal(parsed.accountId,account);
  assert.throws(()=>parseCredentials(`INGESTION_API_KEY=${key}\nINGESTION_API_KEY=${key}\nINGESTION_ACCOUNT_ID=${account}`));
  assert.throws(()=>parseCredentials('DB_PASSWORD=not-a-credential'));
});

test('accepts only recognized receipt states and canonical IDs',()=>{
  assert.equal(validReceipt({ingestion_id:eventId,status:'RECEIVED'}),true);
  assert.equal(validReceipt({ingestion_id:eventId,status:'INVENTED'}),false);
  assert.equal(validReceipt({ingestion_id:'<script>',status:'RECEIVED'}),false);
  assert.equal(stateLabel('REVIEW'),'Por revisar');
  assert.equal(validReceipt({ingestion_id:eventId,status:'PROCESSED'}),false);
  assert.equal(validReceipt({ingestion_id:eventId,status:'PROCESSED',transaction_id:account}),true);
  assert.equal(validReceipt({ingestion_id:eventId,status:'PROCESSED',transaction_id:'invalid'}),false);
});
