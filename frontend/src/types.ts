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
    type: 'POST' | 'COMMENT' // 글 신고인지 댓글 신고인지
    id: number // 글 id 또는 댓글 id
    postId: number // 어느 글로 가야 하는지 (댓글이어도 글 id)
    preview: string // 내용 미리보기
    author: { id: number; nickname: string } | null
  }
  reporter: { id: number; nickname: string } | null
  reason: ReportReason
  detail: string | null
  status: ReportStatus
  createdAt: string
}
