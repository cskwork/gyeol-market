// API types mirroring the backend DTOs (com.olive.commerce.*).
// BigDecimal serializes to a JSON number via Jackson defaults.

export interface PageMeta {
  page: number
  size: number
  total: number
}

export interface FieldErrorEntry {
  field: string
  message: string
  rejectedValue: unknown
}

export interface ErrorBody {
  code: string
  message: string
  path?: string
  traceId?: string
  fieldErrors?: FieldErrorEntry[]
}

// ApiResponse<T> uses @JsonInclude(NON_NULL): null fields are omitted.
export interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: ErrorBody
  meta?: PageMeta
}

export type SortOption = 'POPULAR' | 'LATEST' | 'PRICE_ASC' | 'PRICE_DESC' | 'RATING'

// GET /api/products -> ProductDtos.PublicListItem
export interface ProductListItem {
  productId: number
  brandName: string | null
  productName: string
  salePrice: number
  originalPrice: number
  discountRate: number
  thumbnailUrl: string | null
  rating: number
  reviewCount: number
}

// GET /api/products/{id} -> ProductDtos.PublicDetailResponse
export interface ProductOptionSummary {
  optionId: number
  optionName: string
  optionPrice: number
  // ProductOption.OptionStatus, serialized by name
  status: 'ON_SALE' | 'SOLD_OUT' | 'STOPPED' | 'HIDDEN'
  availableQuantity: number | null
}

export interface ProductImageDetail {
  imageId: number
  url: string
  sortOrder: number
  isThumbnail: boolean
}

export interface CategoryPath {
  categoryId: number
  categoryName: string
  categorySlug: string
}

// --- Auth (POST /api/auth/*) ---------------------------------------------------

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  expiresInSec: number
}

export interface SignupRequest {
  email: string
  password: string
  name: string
  phone?: string
}

export interface SignupResponse {
  memberId: number
}

// --- Cart (GET /api/cart, POST /api/cart/items, etc.) --------------------------

export interface CartItem {
  cartItemId: number
  productOptionId: number
  optionName: string
  productName: string
  salePrice: number
  onSale: boolean
  availableQuantity: number | null
  quantity: number
  lineSubtotal: number
  productStatus: string
}

export interface CartResponse {
  items: CartItem[]
  totalItemCount: number
  totalAmount: number
}

export interface AddToCartRequest {
  productOptionId: number
  quantity: number
}

export interface AddToCartResponse {
  cartItemId: number
  quantity: number
}

export interface UpdateCartQuantityRequest {
  quantity: number
}

// --- Addresses (GET/POST/PATCH /api/me/addresses) ------------------------------

export interface AddressResponse {
  id: number
  recipientName: string
  phone: string
  zipcode: string
  addressMain: string
  addressDetail: string | null
  isDefault: boolean
}

export interface CreateAddressRequest {
  recipientName: string
  phone: string
  zipcode: string
  addressMain: string
  addressDetail?: string
  isDefault: boolean
}

// --- Orders (POST /api/orders) -------------------------------------------------

export interface OrderItemRequest {
  productOptionId: number
  quantity: number
}

export interface CreateOrderRequest {
  items: OrderItemRequest[]
  couponId?: number | null
  usePointAmount?: number | null
  deliveryAddressId: number
}

export interface CreateOrderResponse {
  orderNo: string
  paymentKey: number
  amount: number
  pgCheckoutPayload: { pg: string; payload: unknown } | null
}

// --- Payment (POST /api/payments/confirm) --------------------------------------

export interface ConfirmPaymentRequest {
  orderNo: string
  paymentKey: string
  amount: number
}

export interface ConfirmPaymentResponse {
  orderId: number
  orderNo: string
  status: string
  paymentKey: string
}

// --- Order detail (GET /api/orders/:orderNo) -----------------------------------

export interface OrderItemResponse {
  id: number
  productName: string
  optionName: string
  unitPrice: number
  quantity: number
  totalAmount: number
}

export interface OrderDetailDelivery {
  recipientName: string
  phone: string
  zipcode: string
  addressMain: string
  addressDetail: string | null
}

export interface OrderDetail {
  id: number
  orderNo: string
  status: string
  totalProductAmount: number
  discountAmount: number
  pointUsedAmount: number
  deliveryFee: number
  finalPaymentAmount: number
  items: OrderItemResponse[]
  delivery: OrderDetailDelivery
  createdAt: string
}

// --- Cart merge (POST /api/cart/merge) -----------------------------------------

export interface CartMergeResponse {
  mergedItemCount: number
}

// --- Search (GET /api/search/*) ------------------------------------------------

/** Sort options supported by the search endpoint (maps to SearchDtos.SortOption). */
export type SearchSortOption = 'RELEVANCE' | 'POPULAR' | 'LATEST' | 'PRICE_ASC' | 'PRICE_DESC' | 'RATING'

export interface SearchProductsParams {
  keyword?: string
  categoryId?: number
  brandId?: number
  sort?: SearchSortOption
  page?: number
  size?: number
}

export interface AutocompleteResponse {
  suggestions: string[]
}

export interface PopularKeyword {
  keyword: string
  rank: number
}

export interface PopularResponse {
  keywords: PopularKeyword[]
}

// --- Wishlist (GET/POST/DELETE /api/me/wishlist) --------------------------------

export interface WishlistItem {
  wishlistId: number
  productId: number
  productName: string
  brandName: string | null
  thumbnailUrl: string | null
  salePrice: number
  originalPrice: number
  discountRate: number
}

// --- MyPage summary (GET /api/me/summary) --------------------------------------

export interface MySummary {
  pointBalance: number
  usableCouponCount: number
  totalOrderCount: number
  gradeName: string
}

// --- Order list (GET /api/orders) ----------------------------------------------

export interface MyOrderListItem {
  id: number
  orderNo: string
  status: string
  totalProductAmount: number
  finalPaymentAmount: number
  createdAt: string
}

// -------------------------------------------------------------------------------

export interface ProductDetail {
  productId: number
  brandName: string | null
  brandLogoUrl: string | null
  productName: string
  description: string | null
  salePrice: number
  originalPrice: number
  discountRate: number
  options: ProductOptionSummary[]
  images: ProductImageDetail[]
  categories: CategoryPath[]
  rating: number
  reviewCount: number
}
