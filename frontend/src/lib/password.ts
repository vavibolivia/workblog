/** 새 비밀번호 규칙(서버 PasswordPolicy 와 같다): 최소 길이(기본 8) + 영문과 숫자를 모두. 문제 없으면 null. */
export const PASSWORD_MIN_LENGTH = 8

export function passwordProblem(pw: string, min = PASSWORD_MIN_LENGTH): string | null {
  if (pw.length < min) return `${min}자 이상이어야 합니다.`
  if (pw.length > 100) return '100자 이하여야 합니다.'
  if (!/[A-Za-z]/.test(pw) || !/[0-9]/.test(pw)) return '영문과 숫자를 모두 넣어야 합니다.'
  return null
}

export const passwordHint = (min = PASSWORD_MIN_LENGTH) => `${min}자 이상, 영문과 숫자 포함`
