import { apiGet, apiGetPage, apiPost, apiPostWithHeaders } from './api'
import type {
  AddressResponse,
  CreateAddressRequest,
  CreateOrderRequest,
  CreateOrderResponse,
  ConfirmPaymentRequest,
  ConfirmPaymentResponse,
  OrderDetail,
  MyOrderListItem,
  PageMeta,
} from './types'

// --- Addresses ----------------------------------------------------------------

export function fetchAddresses(signal?: AbortSignal): Promise<AddressResponse[]> {
  return apiGet<AddressResponse[]>('/me/addresses', signal)
}

export function createAddress(body: CreateAddressRequest): Promise<AddressResponse> {
  return apiPost<AddressResponse>('/me/addresses', body)
}

// --- Orders -------------------------------------------------------------------

/** POST /api/orders — creates an order (8-step pipeline). Idempotency-Key is required. */
export function createOrder(
  body: CreateOrderRequest,
  idempotencyKey: string,
): Promise<CreateOrderResponse> {
  return apiPostWithHeaders<CreateOrderResponse>('/orders', body, {
    'Idempotency-Key': idempotencyKey,
  })
}

/** POST /api/payments/confirm — mock PG confirm. */
export function confirmPayment(
  body: ConfirmPaymentRequest,
  idempotencyKey: string,
): Promise<ConfirmPaymentResponse> {
  return apiPostWithHeaders<ConfirmPaymentResponse>('/payments/confirm', body, {
    'Idempotency-Key': idempotencyKey,
  })
}

/** GET /api/orders/:orderNo — order detail. */
export function fetchOrderDetail(orderNo: string, signal?: AbortSignal): Promise<OrderDetail> {
  return apiGet<OrderDetail>(`/orders/${orderNo}`, signal)
}

/** GET /api/orders?status=&page=&size= — paginated order list for the current member. */
export function listMyOrders(
  params: { status?: string; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<{ data: MyOrderListItem[]; meta: PageMeta | undefined }> {
  const qs = new URLSearchParams()
  if (params.status) qs.set('status', params.status)
  qs.set('page', String(params.page ?? 0))
  qs.set('size', String(params.size ?? 10))
  return apiGetPage<MyOrderListItem[]>(`/orders?${qs.toString()}`, signal)
}

/**
 * The server answers INSUFFICIENT_POINTS for several causes with one generic
 * message ("보유 포인트 범위 안에서 정수로 입력해주세요."); name the actual cause.
 * An in-range integer the server still rejects means the balance changed.
 */
export function insufficientPointsMessage(requested: number, orderSubtotal: number): string {
  if (!Number.isInteger(requested) || requested < 0) return '포인트는 0 이상의 정수로 입력해주세요.'
  if (requested > orderSubtotal) return '포인트는 주문금액을 초과할 수 없습니다.'
  return '보유 포인트가 부족합니다. 보유 포인트를 확인한 뒤 다시 입력해주세요.'
}
