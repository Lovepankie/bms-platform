import type { Me } from '../api/client';
import type { Tour, TourStep } from './model';

// Page tours for returns, customer accounts and purchasing (issue #337): the screens a person reaches by opening a row of a list
// or by tapping a button on one. Each tour is for whoever may open the page (the permission the page checks, and the capability
// that switches it on). Plain words, short sentences, say what to tap. tours-retail-purchasing.test.tsx renders each page and
// fails if an anchor is missing.

const SALE_RETURN = 'retail.sale.return';
const SALE_READ = 'retail.sale.read';
const PURCHASE_CREATE = 'retail.purchase.create';
const PAYABLE_MANAGE = 'retail.payable.manage';

const held = (me: Me, permission: string) => (me.permissions ?? []).includes(permission);
const capable = (me: Me, capability: string) => (me.module_capabilities?.['retail'] ?? []).includes(capability);

/** Who may open the page: the permissions it checks, and the capability that switches it on. */
const returner = (me: Me) => held(me, SALE_RETURN) && held(me, SALE_READ) && capable(me, 'returns');
const accountant = (me: Me) => held(me, SALE_READ) && capable(me, 'customer_accounts');
const purchaser = (me: Me) => held(me, PURCHASE_CREATE) && capable(me, 'purchasing');
const payer = (me: Me) => held(me, PAYABLE_MANAGE) && capable(me, 'purchasing');
/** Paying suppliers, what is owed and the statement need no capability (issue #557, ADR-084). */
const owner = (me: Me) => held(me, PAYABLE_MANAGE);

const step = (id: string, target: string, title: string, body: string, more: Partial<TourStep> = {}): TourStep => ({ id, target, module: 'retail', title, body, ...more });

const page = (id: string, title: string, path: string, audience: (me: Me) => boolean, steps: TourStep[], version = 1): Tour => ({
  id: `page-${id}`,
  version,
  title: `This page: ${title}`,
  audience,
  autoStart: false,
  page: `/staff/retail/${path}`,
  steps,
});

export const RETAIL_PURCHASING_TOURS: readonly Tour[] = [
  page('return-new', 'take goods back', 'returns/new/$saleId', returner, [
    step('lines', 'return-lines', 'What comes back', 'Each line shows what was sold and what can still come back. Type how many are coming back, then say why and where the goods go.'),
    step('refund', 'return-refund', 'How the customer is repaid', 'Choose cash, mobile money, bank, or take it off what the customer owes. The credit note total shows before you save.'),
    step('save', 'return-save', 'Record the return', 'Tap Record the return. The credit note is made, and damaged goods never go back into stock.'),
  ]),
  page('credit-note', 'a credit note', 'returns/$returnId', returner, [
    step('summary', 'credit-note', 'The credit note', 'This is the note for goods that came back: the date, the sale, the buyer and how it was settled. Tap the buyer to open their account.'),
    step('lines', 'credit-note-lines', 'The goods', 'Each line shows what came back, why, where it went and what it was credited.'),
    step('totals', 'credit-note-totals', 'The money', 'This shows the total, the part taken off what the buyer owes and the part paid back.'),
    step('void', 'credit-note-void', 'Undo it', 'Tap Void this credit note and give a reason. The goods come off the shelf again and the money goes back as it was.', { optional: true }),
  ]),
  page('account', 'one customer account', 'accounts/$customerId', accountant, [
    step('summary', 'account-summary', 'What they owe', 'This shows what the buyer owes, their credit limit and their payment terms. An administrator can change the limit and terms here.', { optional: true }),
    step('pay', 'account-pay', 'Take a payment', 'Type the amount and tap Save payment. The oldest sales are paid first, or tick the box to choose which sales it pays.', { optional: true, needs: ['retail.sale.create'] }),
    step('payments', 'account-payments', 'Payments taken', 'Every payment taken on this account is listed here. A wrong one can be reversed, with a reason.'),
    step('statement', 'account-statement', 'The statement', 'Pick the dates to see everything that happened on the account, with the running balance.'),
  ]),
  page('purchase-order-new', 'new purchase order', 'purchase-orders/new', purchaser, [
    step('supplier', 'order-supplier', 'Who and where', 'Choose the supplier and the branch the goods are going to.'),
    step('items', 'order-items', 'Add the items', 'Type part of an item name and tap it to add it. Then type how many you want and what one costs.'),
    step('save', 'order-save', 'Save a draft', 'Tap Save draft order. Nothing is ordered yet. You send the order from its own page.'),
  ]),
  page('purchase-order-edit', 'change a purchase order', 'purchase-orders/$orderId/edit', purchaser, [
    step('supplier', 'order-supplier', 'Who and where', 'Change the supplier or the branch if the order was started wrongly.'),
    step('lines', 'order-lines', 'The items', 'Change how many you want or what one costs. Tap Remove to take an item off.'),
    step('save', 'order-save', 'Save the changes', 'Tap Save changes. A draft can be changed. Once an order is sent it is locked.'),
  ]),
  page('purchase-order', 'one purchase order', 'purchase-orders/$orderId', purchaser, [
    step('summary', 'order-summary', 'The order', 'This shows the supplier, the status and when it is expected. Tap the supplier to see what you owe them.'),
    step('lines', 'order-lines', 'Ordered and received', 'Each line shows how many you ordered, how many have arrived and what it costs.'),
    step('actions', 'order-actions', 'What you can do', 'Change a draft, send it to the supplier, receive goods against it, or cancel it. You only see what the order allows.', { optional: true }),
    step('receipts', 'order-receipts', 'Deliveries', 'Each delivery received against this order is listed below. Tap its number to open it.'),
  ]),
  page('order-receive', 'receive goods for an order', 'purchase-orders/$orderId/receive', purchaser, [
    step('who', 'receive-who', 'The delivery', 'Check the branch and the date. Type the number on the supplier\'s delivery note if there is one.'),
    step('lines', 'receive-lines', 'What really arrived', 'Each line starts with what is still to come. Change it to what really arrived, and the cost if it is different.'),
    step('hold', 'receive-hold', 'Freight still to come', 'Tick this if freight or duty is still to be added. The delivery waits, not costed, until you add it.'),
    step('save', 'receive-save', 'Save', 'Tap Save goods received. The goods go into stock at the cost you typed.'),
  ]),
  page('goods-received', 'goods received', 'goods-received', purchaser, [
    step('new', 'receipts-new', 'No order', 'Tap here when goods came with no purchase order. For an order, receive from the order itself.'),
    step('search', 'receipts-search', 'Find a delivery', 'Type a receipt number, an order number, a delivery note number or a supplier\'s name.'),
    step('list', 'receipts-list', 'Your deliveries', 'Tap Open on a delivery to see its goods, or to void it if it was wrong.'),
  ]),
  page('goods-receipt', 'one delivery', 'goods-received/$receiptId', purchaser, [
    step('summary', 'receipt-summary', 'The delivery', 'This shows the supplier, the order and where the goods went. Tap the order number to open the order.'),
    step('lines', 'receipt-lines', 'The goods', 'Each line shows how many arrived and what one cost.'),
    step('landed', 'receipt-landed', 'Freight and duty', 'Tap Add landed cost to share freight or duty over these goods.', { optional: true, needs: [PAYABLE_MANAGE] }),
    step('void', 'receipt-void', 'A wrong delivery', 'Void it with a reason, then receive it again correctly. The goods come out of stock again.', { optional: true }),
  ]),
  page('receive-blind', 'receive goods without an order', 'goods-received/new', purchaser, [
    step('who', 'receive-who', 'Who and where', 'Choose the supplier and the branch the goods go into. Type the delivery note number if there is one.'),
    step('items', 'receive-items', 'Add what arrived', 'Type part of an item name and tap it to add it.'),
    step('lines', 'receive-lines', 'How many and what cost', 'Type how many arrived and what one cost. You can also type a new selling price.'),
    step('save', 'receive-save', 'Save', 'Tap Save goods received. The goods go into stock.'),
  ]),
  page('supplier-bills', 'supplier invoices', 'supplier-bills', payer, [
    step('help', 'bills-help', 'What an invoice is', 'A supplier invoice says what a supplier wants to be paid for goods you received.'),
    step('new', 'bills-new', 'Record an invoice', 'Tap New supplier invoice. Choose the supplier, tick the goods it is for and type what the paper says.'),
    step('search', 'bills-search', 'Find an invoice', 'Type our number, the supplier\'s invoice number or the supplier. Tick the box to see only what is still owed.'),
    step('list', 'bills-list', 'Your invoices', 'Tap Open to see an invoice, pay it, or take a credit note against it.'),
  ]),
  page('supplier-bill-new', 'new supplier invoice', 'supplier-bills/new', payer, [
    step('goods', 'bill-goods', 'The goods', 'Choose the supplier. Then tick the goods received from them that this invoice is for.'),
    step('details', 'bill-details', 'What the paper says', 'Type the invoice number, the dates and the total as printed. The page checks it against what you received.'),
    step('save', 'bill-save', 'Save', 'Tap Save supplier invoice. If it differs from the goods by a lot, you will be asked why.'),
  ]),
  page('supplier-bill', 'one supplier invoice', 'supplier-bills/$billId', payer, [
    step('summary', 'bill-summary', 'The invoice', 'This shows what was billed, what is paid and what is still owed. Tap the supplier to see everything you owe them.'),
    step('lines', 'bill-lines', 'Goods and prices', 'Each line shows what the invoice says against what the goods were received at. Tap a delivery number to open it.'),
    step('pay', 'bill-pay', 'Pay it', 'Tap Pay this supplier to start a payment for them.', { optional: true }),
    step('credit', 'bill-credit', 'Take a credit note', 'Tap Credit note if the supplier took goods back or charged too much.', { optional: true }),
  ]),
  page('supplier-payments', 'supplier payments', 'supplier-payments', owner, [
    step('new', 'payments-new', 'Pay a supplier', 'Tap Pay a supplier. Choose who, how much and how you paid.'),
    step('search', 'payments-search', 'Find a payment', 'Type a payment number, a reference or a supplier\'s name.'),
    step('list', 'payments-list', 'Your payments', 'Tap Open on a payment to see what it paid, or to reverse it.'),
  ], 2),
  page('supplier-payment-new', 'pay a supplier', 'supplier-payments/new', owner, [
    step('supplier', 'payment-supplier', 'Who you are paying', 'Choose the supplier. You then see what they are owed, oldest first.'),
    step('amount', 'payment-amount', 'How much, how and when', 'Choose the branch the money comes from, how you paid, the amount and the day you paid. The day cannot be in the future.'),
    step('allocation', 'payment-allocation', 'What it pays', 'Leave it on the oldest first, or choose Let me choose to give an amount to each purchase or invoice.'),
    step('save', 'payment-save', 'Save', 'Tap Save payment. The whole amount must clear something the supplier is owed.'),
  ], 2),
  page('supplier-payment', 'one supplier payment', 'supplier-payments/$paymentId', owner, [
    step('summary', 'payment-summary', 'The payment', 'This shows who was paid, how much, how and when. Tap the supplier to see what is still owed to them.'),
    step('allocations', 'payment-allocations', 'What it paid', 'Each line is a purchase or an invoice this payment cleared.'),
    step('reverse', 'payment-reverse', 'Undo it', 'Reverse the payment with a reason. The money goes back and the supplier is owed it again.', { optional: true }),
  ], 2),
  page('supplier-payables', 'what I owe suppliers', 'supplier-payables', owner, [
    step('help', 'payables-help', 'What this shows', 'What each supplier is owed for stock bought on credit, less what you paid them.'),
    step('links', 'payables-links', 'Pay or look back', 'Tap Pay a supplier to record a payment, or Supplier payments to see the ones made.'),
    step('report', 'payables-report', 'Supplier by supplier', 'The total comes first. Tap Pay on a supplier to pay them, or Statement to see everything with them.'),
  ], 2),
  page('supplier-statement', 'a supplier statement', 'suppliers/$supplierId/statement', owner, [
    step('links', 'supplier-statement-links', 'Pay from here', 'Tap Pay this supplier to record a payment to them.'),
    step('dates', 'supplier-statement-dates', 'Pick the dates', 'Choose the first and last day. The last 90 days show at first.'),
    step('lines', 'supplier-statement-lines', 'What happened', 'Each purchase on credit and each payment, with what was owed after it.'),
    step('share', 'supplier-statement-share', 'Send it', 'Tap Share the statement to send it to the supplier, on WhatsApp or another app.'),
  ]),
  page('landed-cost-new', 'new landed cost', 'landed-costs/new', payer, [
    step('who', 'landed-who', 'Who is owed', 'Choose the forwarder, carrier or agent, and type their invoice number and the dates.'),
    step('charges', 'landed-charges', 'The charges', 'Add each charge: freight, duty, clearing or insurance. Type the invoice total as printed.'),
    step('method', 'landed-method', 'How to share them', 'Choose how the charges are spread over the goods: by value, by quantity, by weight, or amounts you type.'),
    step('goods', 'landed-goods', 'The goods', 'Find the deliveries the charges are for and add them. Then tap Review to see each line\'s share before you save.'),
  ]),
  page('landed-cost', 'one landed cost', 'landed-costs/$landedCostId', payer, [
    step('summary', 'landed-summary', 'The landed cost', 'This shows who is owed, how it was shared and how much is still owed. Tap the supplier to see everything you owe them.'),
    step('charges', 'landed-charges-table', 'The charges', 'These are the freight, duty and other charges that were recorded.'),
    step('shared', 'landed-shared', 'How it was shared', 'Each line shows the share it took. Tap a delivery number to open the delivery.'),
    step('void', 'landed-void', 'Undo it', 'Void it with a reason. The charges come off the cost of the goods.', { optional: true }),
  ]),
  page('supplier-credits', 'supplier credit notes', 'supplier-credits', payer, [
    step('help', 'credits-help', 'What a credit note is', 'It is what a supplier gives you when you send goods back, or when an invoice charged too much.'),
    step('new', 'credit-new', 'Record one', 'Tap New credit note, then find the supplier invoice it is against.'),
    step('search', 'credits-search', 'Find a credit note', 'Type our number, the credit note number or the supplier.'),
    step('list', 'credits-list', 'Your credit notes', 'Tap Open to see the lines, or to void one.'),
  ]),
  page('supplier-credit-new', 'new supplier credit note', 'supplier-credits/new', payer, [
    step('invoice', 'credit-invoice', 'Find the invoice', 'Type the invoice number or the supplier, then tap Credit this invoice.', { optional: true }),
    step('kind', 'credit-kind', 'What it is for', 'Choose goods sent back, or a price taken off.', { optional: true }),
    step('lines', 'credit-lines', 'The lines', 'Type how many were sent back, or how much was taken off each line. The limits are shown.', { optional: true }),
    step('save', 'credit-save', 'Save', 'Type the supplier\'s credit note number and total, then tap Save credit note.', { optional: true }),
  ]),
  page('supplier-credit', 'one supplier credit note', 'supplier-credits/$creditId', payer, [
    step('summary', 'credit-summary', 'The credit note', 'This shows the supplier, the invoice it is against and why it was given. Tap the invoice number to open it.'),
    step('lines', 'credit-detail-lines', 'The lines', 'Each line shows what was sent back or taken off, and the credit it gave.'),
    step('void', 'credit-void', 'Undo it', 'Void it with a reason. The invoice is owed again for what the credit note took off.', { optional: true }),
  ]),
];
