import { useSearchParams } from 'react-router'
import type { School, VisaOption, VisaReviewOptions } from '../types'
import { LOADING_TEXT } from './loadingText'

// 주소 쿼리에 두는 거르기 값의 이름 (목록·통계 화면 공용)
type FilterName = 'schoolId' | 'visaType' | 'applicationType'

// 드롭다운 하나. 주소의 값이 목록에 없으면 임시 option을 끼워 넣어 select가 주소를 그대로 보여 주게 한다
// (안 그러면 "전체"로 보이는데 "전체"를 골라도 변화가 없어 빠져나올 수 없다). 목록을 받는 중이면 "불러오는 중..."
function FilterSelect({
  label,
  allLabel,
  unknownLabel,
  value,
  items,
  pending,
  onChange,
}: {
  label: string
  allLabel: string
  unknownLabel: string
  value: string
  items: { value: string; name: string }[] | null
  pending: boolean
  onChange: (value: string) => void
}) {
  const missing = value !== '' && !(items ?? []).some((i) => i.value === value)
  return (
    <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{allLabel}</option>
      {missing && <option value={value}>{pending ? LOADING_TEXT : `알 수 없는 ${unknownLabel} (${value})`}</option>}
      {(items ?? []).map((i) => (
        <option key={i.value} value={i.value}>
          {i.name}
        </option>
      ))}
    </select>
  )
}

// 학교·비자 종류·신청 종류 드롭다운 3개. 값의 "진짜 위치"는 주소 쿼리다(새로고침·뒤로 가기가 자연스럽게 맞는다)
// 목록 화면과 통계 화면이 같이 쓴다. 선택지 목록은 부모가 useVisaFilterOptions로 받아 넘긴다
export default function VisaReviewFilters({
  schools,
  options,
  schoolsFailed,
  optionsFailed,
}: {
  schools: School[] | null
  options: VisaReviewOptions | null
  schoolsFailed: boolean
  optionsFailed: boolean
}) {
  const [searchParams, setSearchParams] = useSearchParams()

  // 바꾸면 주소만 바꾼다. 그러면 화면이 알아서 다시 불러온다
  function change(name: FilterName, value: string) {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(name, value)
    else next.delete(name) // "전체"는 주소에서 뺀다
    setSearchParams(next)
  }

  const toItems = (list: VisaOption[] | undefined) => list?.map((o) => ({ value: o.code, name: o.name })) ?? null

  return (
    <div className="filters">
      <FilterSelect
        label="학교"
        allLabel="전체 학교"
        unknownLabel="학교"
        value={searchParams.get('schoolId') ?? ''}
        items={schools?.map((s) => ({ value: String(s.id), name: s.name })) ?? null}
        pending={schools === null && !schoolsFailed}
        onChange={(v) => change('schoolId', v)}
      />
      <FilterSelect
        label="비자 종류"
        allLabel="전체 비자 종류"
        unknownLabel="비자 종류"
        value={searchParams.get('visaType') ?? ''}
        items={toItems(options?.visaTypes)}
        pending={options === null && !optionsFailed}
        onChange={(v) => change('visaType', v)}
      />
      <FilterSelect
        label="신청 종류"
        allLabel="전체 신청 종류"
        unknownLabel="신청 종류"
        value={searchParams.get('applicationType') ?? ''}
        items={toItems(options?.applicationTypes)}
        pending={options === null && !optionsFailed}
        onChange={(v) => change('applicationType', v)}
      />
    </div>
  )
}
