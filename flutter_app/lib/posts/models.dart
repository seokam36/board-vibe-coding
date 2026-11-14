// 서버가 돌려주는 글·학교·말머리의 모양 (React의 types.ts). 서버 JSON(Map)을 Dart 객체로 바꾼다.

// 학교 한 곳 (GET /schools, 글 응답 안의 school)
class School {
  const School({required this.id, required this.name, this.emailDomains = const []});

  final int id;
  final String name;
  // 인증용 메일 도메인 (GET /schools 에만 있다. 글 응답의 school에는 없어 빈 목록)
  final List<String> emailDomains;

  factory School.fromJson(Map<String, dynamic> json) => School(
        id: json['id'] as int,
        name: json['name'] as String,
        emailDomains: ((json['emailDomains'] as List<dynamic>?) ?? const []).cast<String>(),
      );
}

// 말머리 한 종류 (GET /categories, 글 응답 안의 category). code는 요청에 보내는 값, name은 화면에 보여 줄 이름
class Category {
  const Category({required this.code, required this.name});

  final String code;
  final String name;

  factory Category.fromJson(Map<String, dynamic> json) =>
      Category(code: json['code'] as String, name: json['name'] as String);
}

// 글쓴이 (id, 닉네임). 옛 글·탈퇴한 사람은 글의 author가 null
class PostAuthor {
  const PostAuthor({required this.id, required this.nickname});

  final int id;
  final String nickname;

  factory PostAuthor.fromJson(Map<String, dynamic> json) =>
      PostAuthor(id: json['id'] as int, nickname: json['nickname'] as String);
}

// 글 한 건
class Post {
  const Post({
    required this.id,
    required this.title,
    required this.content,
    required this.author,
    required this.school,
    required this.category,
    required this.createdAt,
    required this.commentCount,
    required this.likeCount,
  });

  final int id;
  final String title;
  final String content;
  final PostAuthor? author; // 작성자. 옛 글은 null
  final School? school; // 학교. 학교 없는 글은 null
  final Category category; // 말머리
  final String createdAt; // ISO-8601 UTC 문자열. 화면에는 formatDate를 거쳐서만 보여 준다
  final int commentCount;
  final int likeCount;

  factory Post.fromJson(Map<String, dynamic> json) {
    final author = json['author'];
    final school = json['school'];
    return Post(
      id: json['id'] as int,
      title: json['title'] as String,
      content: json['content'] as String,
      author: author == null ? null : PostAuthor.fromJson(author as Map<String, dynamic>),
      school: school == null ? null : School.fromJson(school as Map<String, dynamic>),
      category: Category.fromJson(json['category'] as Map<String, dynamic>),
      createdAt: json['createdAt'] as String,
      commentCount: json['commentCount'] as int,
      likeCount: json['likeCount'] as int,
    );
  }
}

// 댓글 한 건 (React의 Comment). 작성자가 탈퇴했으면 author가 null
class Comment {
  const Comment({required this.id, required this.content, required this.author, required this.createdAt});

  final int id;
  final String content;
  final PostAuthor? author;
  final String createdAt; // ISO-8601 UTC 문자열. 화면에는 formatDate를 거쳐서만 보여 준다

  factory Comment.fromJson(Map<String, dynamic> json) {
    final author = json['author'];
    return Comment(
      id: json['id'] as int,
      content: json['content'] as String,
      author: author == null ? null : PostAuthor.fromJson(author as Map<String, dynamic>),
      createdAt: json['createdAt'] as String,
    );
  }
}
