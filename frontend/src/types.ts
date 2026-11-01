// 서버가 돌려주는 글 한 건의 모양
export interface Post {
  id: number
  title: string
  content: string
  createdAt: string // ISO-8601 UTC 문자열. 화면에는 formatDate를 거쳐서만 보여준다
}

// 글 작성할 때 서버에 보내는 값 (id, createdAt은 서버가 만든다)
export interface PostCreateRequest {
  title: string
  content: string
}
