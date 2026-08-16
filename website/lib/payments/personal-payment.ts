import { randomInt } from "node:crypto";

export function availablePaymentAmounts(
  listAmountMinor: number,
  discountMaxMinor: number,
  occupiedAmounts: ReadonlySet<number>,
): number[] {
  if (!Number.isSafeInteger(listAmountMinor) || listAmountMinor <= 0) {
    throw new Error("INVALID_LIST_AMOUNT");
  }
  if (!Number.isSafeInteger(discountMaxMinor) || discountMaxMinor < 0) {
    throw new Error("INVALID_DISCOUNT_RANGE");
  }

  const minimumAmount = Math.max(1, listAmountMinor - discountMaxMinor);
  const available: number[] = [];
  for (let amount = minimumAmount; amount <= listAmountMinor; amount += 1) {
    if (!occupiedAmounts.has(amount)) available.push(amount);
  }
  return available;
}

export function selectPaymentAmount(
  listAmountMinor: number,
  discountMaxMinor: number,
  occupiedAmounts: ReadonlySet<number>,
): number {
  const available = availablePaymentAmounts(
    listAmountMinor,
    discountMaxMinor,
    occupiedAmounts,
  );
  if (available.length === 0) {
    throw new Error("PAYMENT_AMOUNT_POOL_EXHAUSTED");
  }
  return available[randomInt(available.length)];
}
