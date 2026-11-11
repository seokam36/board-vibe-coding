import { useEffect, useRef } from 'react'
import type { FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import type { School } from '../types'
import { LOADING_TEXT } from './loadingText'

// 주소 쿼리에 두는 거르기 값의 이름
type FilterName = 'schoolId' | 'tradeType' | 'status'

// 제목 검색어 최대 글자 수 (서버와 같은 50자)
const MAX_SEARCH_LENGTH = 50

// 판매/나눔, 상태의 고정 선택지 (value는 서버가 받는 값, name은 화면 글자)
const TRADE_TYPES = [
  { value: 'SALE', name: '판매' },
  { value: 'FREE', name: '나눔' },
]
const STATUSES = [
  { value: 'ON_SALE', name: '판매중' },
  { value: 'RESERVED', name: '예약중' },
  { value: 'DONE', name: '거래완료' },
]

// 드롭다운 하나. 주소의 값이 선택지에 없으면 임시 option을 끼워 넣어 select가 주소를 그대로 보여 주게 한다
// (안 그러면 "전체"로 보이는데 "전체"를 골라도 변화가 없어 빠져나올 수 없다)
function Select({
  label,
  allLabel,
  value,
  items,
  pending,
  onChange,
}: {
  label: string
  allLabel: string
  value: string
  items: { value: string; name: string }[]
  pending: boolean
  onChange: (value: string) => void
}) {
  const missing = value !== '' && !items.some((i) => i.value === value)
  return (
    <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{allLabel}</option>
      {missing && <option value={value}>{pending ? LOADING_TEXT : `알 수 없는 ${label} (${value})`}</option>}
      {items.map((i) => (
        <option key={i.value} value={i.value}>
          {i.name}
        </option>
      ))}
    </select>
  )
}

// 학교·판매/나눔·상태 드롭다운 3개 + 제목 검색창. 값의 "진짜 위치"는 주소 쿼리다(새로고침·뒤로 가기가 자연스럽게 맞는다)
export default function MarketFilters({ schools, schoolsFailed }: { schools: School[] | null; schoolsFailed: boolean }) {
  const [searchParams, setSearchParams] = useSearchParams()

  // 바꾸면 주소만 바꾼다. 그러면 화면이 알아서 다시 불러온다
  function change(name: FilterName, value: string) {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(name, value)
    else next.delete(name) // "전체"는 주소에서 뺀다
    setSearchParams(next)
  }

  const q = searchParams.get('q') ?? ''

  // 검색 입력칸(uncontrolled). 주소의 q가 바뀌면 값만 맞춘다
  const inputRef = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (inputRef.current) inputRef.current.value = q
  }, [q])

  // 검색어 제출: 앞뒤 공백을 뺀 값을 주소 q에 넣는다. 비면 q를 주소에서 뺀다
  function submitSearch(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const value = String(new FormData(e.currentTarget).get('q') ?? '').trim()
    const next = new URLSearchParams(searchParams)
    if (value) next.set('q', value)
    else next.delete('q')
    setSearchParams(next)
  }

  return (
    <div className="filters">
      <Select
        label="학교"
        allLabel="전체 학교"
        value={searchParams.get('schoolId') ?? ''}
        items={(schools ?? []).map((s) => ({ value: String(s.id), name: s.name }))}
        pending={schools === null && !schoolsFailed}
        onChange={(v) => change('schoolId', v)}
      />
      <Select
        label="판매/나눔"
        allLabel="판매·나눔 전체"
        value={searchParams.get('tradeType') ?? ''}
        items={TRADE_TYPES}
        pending={false}
        onChange={(v) => change('tradeType', v)}
      />
      <Select
        label="상태"
        allLabel="전체 상태"
        value={searchParams.get('status') ?? ''}
        items={STATUSES}
        pending={false}
        onChange={(v) => change('status', v)}
      />
      {/* 제목 검색: 제출(검색 버튼·Enter)하면 주소의 q만 바꾸고 다른 필터는 그대로 둔다.
          주소의 q가 바뀌면(뒤로 가기 등) useEffect가 입력칸 값만 맞춘다. 칸을 다시 만들지 않아 Enter 뒤에도 포커스가 남는다 */}
      <form role="search" className="filters" onSubmit={submitSearch}>
        <input
          ref={inputRef}
          name="q"
          type="search"
          aria-label="제목 검색어"
          placeholder="제목으로 검색"
          maxLength={MAX_SEARCH_LENGTH}
          defaultValue={q}
        />
        <button type="submit" className="btn">
          검색
        </button>
      </form>
    </div>
  )
}
