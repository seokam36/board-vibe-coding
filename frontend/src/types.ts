// 학교 한 곳 (GET /schools, 글 응답 안의 school)
export interface School {
  id: number
  name: string
  emailDomains?: string[] // 인증 메일로 쓸 수 있는 도메인들 (GET /schools 응답에만 있다)
}

// 말머리 한 종류 (GET /categories, 글 응답 안의 category). code는 요청에 보내는 값, name은 화면에 보여 줄 이름
export interface Category {
  code: string
  name: string
}

// 서버가 돌려주는 글 한 건의 모양
export interface Post {
  id: number
  title: string
  content: string
  author: { id: number; nickname: string } | null // 작성자. 옛 글은 null
  school: School | null // 학교. 학교 없는 글은 null
  category: Category // 말머리
  createdAt: string // ISO-8601 UTC 문자열. 화면에는 formatDate를 거쳐서만 보여준다
  commentCount: number // 댓글 수 (S8)
  likeCount: number // 공감 수 (S8)
}

// 댓글 한 건 (GET /posts/{id}/comments). 작성자가 탈퇴했으면 author는 null
export interface Comment {
  id: number
  postId: number
  content: string
  createdAt: string
  author: { id: number; nickname: string } | null
}

// 공감·공감 취소 응답: 처리 후 공감 수와 내가 공감한 상태
export interface LikeResult {
  likeCount: number
  liked: boolean
}

// 글 작성할 때 서버에 보내는 값 (id, createdAt은 서버가 만든다). 학교·말머리는 필수
export interface PostCreateRequest {
  title: string
  content: string
  schoolId: number // 학교 id
  category: string // 말머리 code
}

// 글 수정할 때 서버에 보내는 값. 학교는 바꿀 수 없어서 schoolId가 없다
export interface PostUpdateRequest {
  title: string
  content: string
  category: string // 말머리 code
}

// 신고 사유 한 종류 (GET /report-reasons). code는 요청에 보내는 값, name은 화면에 보여 줄 이름
export interface ReportReason {
  code: string
  name: string
}

// 신고 처리 상태. 미처리(PENDING) / 기각=문제 없음(DISMISSED)
export type ReportStatus = 'PENDING' | 'DISMISSED'

// 관리자 신고 목록의 한 건 (GET /admin/reports). 작성자·신고자가 탈퇴했으면 null
export interface Report {
  id: number
  target: {
    type: 'POST' | 'COMMENT' | 'MARKET_ITEM' | 'CONVERSATION' // 글·댓글·중고 글·대화 중 무엇의 신고인지
    id: number // 글 id 또는 댓글 id 또는 중고 글 id 또는 대화방 id (대화 보기 주소에는 이게 아니라 신고 id를 쓴다)
    postId: number | null // 글·댓글이면 이동할 글 id, 중고 글·대화면 null
    preview: string // 내용 미리보기 (대화는 글 제목)
    author: { id: number; nickname: string } | null // 대화 신고에서는 신고자가 아닌 쪽 참가자
  }
  reporter: { id: number; nickname: string } | null
  reason: ReportReason
  detail: string | null
  status: ReportStatus
  createdAt: string
}

// 관리자가 보는 신고된 대화 (GET /admin/reports/{신고 id}/conversation, docs/api.md 5-15). 사람은 id·nickname뿐, 탈퇴했으면 null
export interface AdminConversationMessage {
  id: number
  sender: { id: number; nickname: string } | null // 보낸 사람이 탈퇴했으면 null
  content: string
  createdAt: string // ISO-8601 UTC 문자열 (formatDate로 표시)
}
export interface AdminConversationView {
  reportId: number
  conversationId: number
  itemTitle: string // 방을 만들 때 복사해 둔 글 제목
  buyer: { id: number; nickname: string } | null
  seller: { id: number; nickname: string } | null
  messages: AdminConversationMessage[] // 오래된 것부터, 최근 200통
  hasMore: boolean // 200통보다 이전 쪽지가 더 있으면 true
}

// 비자 후기 선택지 한 개 (GET /visa-review-options). code는 요청·주소에 쓰는 값, name은 화면에 보여 줄 한글 이름
export interface VisaOption {
  code: string
  name: string
}

// 비자 후기 선택지 묶음 (GET /visa-review-options)
export interface VisaReviewOptions {
  visaTypes: VisaOption[]
  applicationTypes: VisaOption[]
  results: VisaOption[]
}

// 비자 후기 한 건 (GET /visa-reviews, /visa-reviews/{id})
export interface VisaReview {
  id: number
  author: { id: number; nickname: string } | null // 작성자. 탈퇴했으면 null
  school: School
  visaType: VisaOption
  applicationType: VisaOption
  receivedDate: string // yyyy-MM-dd. 화면에는 formatDay를 거쳐서만 보여준다
  resultDate: string // yyyy-MM-dd
  result: VisaOption // 승인(APPROVED) / 불허(DENIED)
  processingDays: number // 접수일~결과일 일수 (서버가 계산)
  content: string // 본문. 안 썼으면 ""
  createdAt: string // ISO-8601 UTC 문자열 (formatDate로 표시)
}

// 비자 후기 통계 한 줄 (GET /visa-reviews/stats): 학교 x 비자 종류 x 신청 종류 조합
export interface VisaReviewStat {
  school: School
  visaType: VisaOption
  applicationType: VisaOption
  count: number // 후기 수
  avgDays: number // 평균 처리 일수 (소수 1자리)
}

// 비자 후기 작성·수정할 때 서버에 보내는 값 (PUT도 같은 모양). 학교는 서버가 정하니 schoolId가 없다
export interface VisaReviewRequest {
  visaType: string // code
  applicationType: string // code
  receivedDate: string // yyyy-MM-dd
  resultDate: string // yyyy-MM-dd
  result: string // code
  content: string // 최대 2000자, 안 써도 된다
}

// 내 비자 일정의 할 일 한 줄 (GET·PUT /users/me/visa-plan 응답의 todos)
export interface VisaTodo {
  code: string // PREPARE / CHECK / URGENT (만료 며칠 전 시점의 이름)
  title: string // 화면에 보여 줄 할 일 문구
  dueDate: string // yyyy-MM-dd
  daysLeft: number // 오늘(서울)부터 남은 일수. 지났으면 음수
}

// 내 비자 일정 (GET·PUT /users/me/visa-plan). 일정이 없으면 expiryDate·daysLeft·stage가 null, todos는 []
export interface VisaPlan {
  expiryDate: string | null // yyyy-MM-dd. 화면에는 formatDay를 거쳐서만 보여준다
  daysLeft: number | null // 만료일까지 남은 일수 (지났으면 음수)
  stage: 'PREPARE' | 'CHECK' | 'URGENT' | 'EXPIRED' | null // 120일 이내부터 값이 생긴다
  todos: VisaTodo[]
}

// 중고 글에 올린 사진 한 장의 정보 (docs/api.md 28번 photos 항목). 사진 파일 자체는 34번 주소로 부른다
export interface MarketItemPhoto {
  id: number
  width: number // 본 사진 크기(긴 변 ≤ 2048)
  height: number
  thumbWidth: number // 썸네일 크기(긴 변 ≤ 320)
  thumbHeight: number
  contentType: string // image/jpeg 또는 image/png
}

// 중고거래·나눔 글 한 건 (GET /market-items/{id}, 올리기·수정·상태 변경 응답). code는 요청·주소에 쓰는 값, name은 서버가 준 한글 이름
export interface MarketItem {
  id: number
  title: string
  content: string
  tradeType: { code: 'SALE' | 'FREE'; name: string } // 판매 / 나눔
  price: number // 원 단위 정수. 나눔은 0
  status: { code: 'ON_SALE' | 'RESERVED' | 'DONE'; name: string } // 판매중 / 예약중 / 거래완료
  school: School
  seller: { id: number; nickname: string } // 판매자 (탈퇴하면 글도 지워져 항상 값이 있다)
  createdAt: string // ISO-8601 UTC 문자열 (formatDate로 표시)
  photos: MarketItemPhoto[] // 올린 순. 사진이 없으면 []
}

// 중고거래·나눔 목록 항목 (GET /market-items). 한 건 응답에서 photos 대신 대표 사진 한 장(coverPhoto)이 있다
export type MarketListItem = Omit<MarketItem, 'photos'> & {
  coverPhoto: MarketItemPhoto | null // 처음 올린 사진. 없으면 null
}

// 중고거래·나눔 글을 올리거나 고칠 때 서버에 보내는 값 (POST·PUT /market-items). 학교·상태·판매자는 서버가 정한다
export interface MarketItemRequest {
  title: string
  content: string
  tradeType: 'SALE' | 'FREE' // 판매 / 나눔
  price?: number // 판매일 때만 보낸다 (1~10,000,000). 나눔이면 아예 보내지 않는다
}

// ---- 쪽지 (docs/api.md 36~41) ----

// 대화에 붙은 물건 글 정보. 글이 지워졌으면 id가 null, deleted가 true (title은 방을 만들 때 복사해 둔 제목)
export interface ConversationItemRef {
  id: number | null
  title: string
  deleted: boolean
}

// 대화 상대. 상대가 탈퇴했으면 응답에서 partner 자체가 null
export interface ConversationPartner {
  id: number
  nickname: string
}

// 쪽지 한 통. mine = 내가 보낸 쪽지인가 (보낸 사람이 탈퇴해 비었으면 false)
export interface ConversationMessage {
  id: number
  content: string
  createdAt: string // ISO-8601 UTC 문자열 (formatDate로 표시)
  mine: boolean
}

// 쪽지함 한 줄 (GET /conversations). 최근 쪽지가 있는 방부터 온다
export interface ConversationSummary {
  id: number
  item: ConversationItemRef
  partner: ConversationPartner | null
  lastMessage: { content: string; createdAt: string; mine: boolean }
  unreadCount: number // 안 읽은 쪽지 수 (쪽지함 줄에 배지로 표시)
}

// 안 읽은 쪽지 수 (GET /conversations/unread-count): 모든 대화를 합친 수. 상단바 "쪽지 (n)"에 쓴다
export interface UnreadCountResponse {
  count: number
}

// 대화 보기 (GET /conversations/{id}). messages는 오래된 것부터, 최근 200통
export interface ConversationDetail {
  id: number
  item: ConversationItemRef
  partner: ConversationPartner | null
  hasMore: boolean // 200통보다 이전 쪽지가 더 있으면 true
  messages: ConversationMessage[]
}

// 판매자에게 첫 쪽지를 보낸 결과 (POST /market-items/{id}/conversations). 새 방이든 기존 방이든 같은 모양
export interface StartConversationResponse {
  conversationId: number
  message: ConversationMessage
}
