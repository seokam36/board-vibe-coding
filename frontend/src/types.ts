// 학교 한 곳 (GET /schools, 글 응답 안의 school)
export interface School {
  id: number
  name: string
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
