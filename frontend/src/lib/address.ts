import type { CreateAddressRequest } from './types'

/** Matches MemberDtos.CreateAddressRequest; accepts a pasted mobile number too. */
export function normalizeAddress(address: CreateAddressRequest): CreateAddressRequest {
  return {
    ...address,
    recipientName: address.recipientName.trim(),
    phone: address.phone.trim().replace(/^(01[016789])(\d{3,4})(\d{4})$/, '$1-$2-$3'),
    zipcode: address.zipcode.trim(),
    addressMain: address.addressMain.trim(),
    addressDetail: address.addressDetail?.trim() || undefined,
  }
}

export function addressError(address: CreateAddressRequest): { field: keyof CreateAddressRequest; message: string } | null {
  const required = [
    ['recipientName', '수령인', 100],
    ['phone', '연락처', 20],
    ['zipcode', '우편번호', 10],
    ['addressMain', '기본 주소', 255],
  ] as const
  for (const [field, label, max] of required) {
    if (!address[field].trim()) return { field, message: `${label}${field === 'recipientName' ? '을' : '를'} 입력해주세요.` }
    if (address[field].length > max) return { field, message: `${label}는 ${max}자 이내로 입력해주세요.` }
  }
  if (!/^01[016789]-\d{3,4}-\d{4}$/.test(address.phone)) {
    return { field: 'phone', message: '연락처를 010-1234-5678 형식으로 입력해주세요.' }
  }
  if ((address.addressDetail?.length ?? 0) > 255) {
    return { field: 'addressDetail', message: '상세 주소는 255자 이내로 입력해주세요.' }
  }
  return null
}
