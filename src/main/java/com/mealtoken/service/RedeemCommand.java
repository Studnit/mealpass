package com.mealtoken.service;

/**
 * @param customerId     whose token is spent (taken from the session, or from a verified pass for staff mode)
 * @param actorUserId    who performed the action (the customer, or the staff member)
 * @param idempotencyKey optional client key; repeats of the same key return the original receipt
 */
public record RedeemCommand(Long customerId, Long hotelId, Long mealSlotId, Long actorUserId,
                            String idempotencyKey, String mode) {}
