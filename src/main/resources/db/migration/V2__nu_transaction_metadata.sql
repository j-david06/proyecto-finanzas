ALTER TABLE accounts ADD COLUMN bank_reference varchar(120)
    CHECK (bank_reference IS NULL OR btrim(bank_reference)<>'');
ALTER TABLE accounts ADD COLUMN card_last4 varchar(4)
    CHECK (card_last4 IS NULL OR (card_last4 ~ '^[0-9]{4}$' AND kind='CREDIT_CARD'));

ALTER TABLE transactions ADD COLUMN original_amount numeric(18,2);
ALTER TABLE transactions ADD COLUMN original_currency varchar(3);
ALTER TABLE transactions ADD COLUMN tax_4x1000 numeric(18,2);
ALTER TABLE transactions ADD COLUMN card_last4 varchar(4) CHECK (card_last4 IS NULL OR card_last4 ~ '^[0-9]{4}$');
ALTER TABLE transactions ADD COLUMN installments integer CHECK (installments IS NULL OR installments BETWEEN 1 AND 1200);
ALTER TABLE transactions ADD COLUMN origin_product varchar(120) CHECK (origin_product IS NULL OR btrim(origin_product)<>'');
ALTER TABLE transactions ADD COLUMN next_cut_on date;
ALTER TABLE transactions ADD CONSTRAINT ck_original_money CHECK (
    (original_amount IS NULL AND original_currency IS NULL) OR
    (original_amount IS NOT NULL AND original_currency IS NOT NULL AND original_amount>0
      AND original_amount<>'NaN'::numeric AND original_currency ~ '^[A-Z]{3}$'));
ALTER TABLE transactions ADD CONSTRAINT ck_tax_4x1000 CHECK (tax_4x1000 IS NULL OR
    (currency='COP' AND tax_4x1000>=0 AND tax_4x1000<=amount AND tax_4x1000<>'NaN'::numeric));
