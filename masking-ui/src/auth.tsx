import { createContext, useContext } from 'react'
import type { RoleCode, UserView } from './api'

// 로그인 사용자. 메뉴·버튼 숨김은 편의일 뿐이고 실제 권한 검사는 서버가 한다

export const AuthContext = createContext<UserView | null>(null)

export function useMe(): UserView {
  const me = useContext(AuthContext)
  if (!me) throw new Error('로그인 정보가 없습니다')
  return me
}

/** 역할 중 하나라도 있으면 true */
export function useHas(...roles: RoleCode[]): boolean {
  const me = useContext(AuthContext)
  return !!me && roles.some((r) => me.roles.includes(r))
}
