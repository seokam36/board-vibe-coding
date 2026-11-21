import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../messages/message_text.dart';
import '../messages/unread_store.dart';
import '../theme/app_colors.dart';

// 아래 탭 한 칸의 정보: 글자, 누르면 갈 주소, 안 골랐을 때(테두리)·골랐을 때(채움) 아이콘
class _Tab {
  const _Tab(this.label, this.path, this.icon, this.selectedIcon);

  final String label;
  final String path;
  final IconData icon;
  final IconData selectedIcon;
}

// 탭 5개 (이 순서가 화면 순서). 쪽지는 index 3이라 아래에서 배지를 붙인다.
const _tabs = [
  _Tab('게시판', '/', Icons.home_outlined, Icons.home),
  _Tab('비자', '/visa-reviews', Icons.flight_takeoff_outlined, Icons.flight_takeoff),
  _Tab('중고', '/market', Icons.storefront_outlined, Icons.storefront),
  _Tab('쪽지', '/messages', Icons.chat_bubble_outline, Icons.chat_bubble),
  _Tab('내 정보', '/me', Icons.person_outline, Icons.person),
];
const _messagesTab = 3;

// 지금 주소가 어느 탭인지 (0~4). 어느 탭도 아니면 -1(404 같은 곳은 선택 없음).
// 주소의 "첫 마디"만 본다: /posts/3/edit 의 첫 마디는 posts. 라우터가 대소문자를 가리지 않으니 소문자로 맞춰 비교한다.
int tabIndexFor(String path) {
  final segments = path.toLowerCase().split('/'); // '/a/b' -> ['', 'a', 'b']
  final first = segments.length > 1 ? segments[1] : '';
  switch (first) {
    case '': // '/'
    case 'posts':
      return 0;
    case 'visa-reviews':
    case 'visa-plan': // 비자 일정은 탭이 아니라 내 정보의 바로가기지만, 비자 쪽 화면이라 비자 탭을 켠다
      return 1;
    case 'market':
    case 'chat': // 중고 글 챗봇
      return 2;
    case 'messages':
      return 3;
    case 'me':
    case 'admin':
    case 'login':
    case 'signup':
      return 4;
    default:
      return -1;
  }
}

// 모든 화면 아래의 탭 막대. 바탕(paper)과 위쪽 선은 화면 끝까지, 안쪽 5칸은 본문 폭(720) 가운데에 모은다.
// NavigationBar를 쓰지 않고 Row로 직접 만든 이유: 선택 표시(색+굵기+채운 아이콘+스크린리더)·쪽지 배지·720 폭을
// NavigationBar의 기본 모양과 싸우지 않고 그대로 맞추려고.
class BottomTabs extends StatelessWidget {
  const BottomTabs({super.key, required this.location});

  final String location; // 지금 화면의 주소(경로). 선택 표시에 쓴다

  @override
  Widget build(BuildContext context) {
    final selected = tabIndexFor(location);
    // 안 읽은 쪽지 수: 위 막대가 쓰던 보관소(UnreadStore)를 그대로 읽는다 (요청이 늘지 않는다)
    final unread = context.watch<UnreadStore>().count;
    // InkWell의 물결이 막대 바탕 위에 그려지도록 Material(paper)을 깐다.
    // 위쪽 선은 DecoratedBox를 foreground(앞)로 그려서 불투명한 paper 바탕에 덮이지 않게 한다.
    return DecoratedBox(
      position: DecorationPosition.foreground,
      decoration: const BoxDecoration(border: Border(top: BorderSide(color: AppColors.line))),
      child: Material(
        color: AppColors.paper,
        child: Center(
          heightFactor: 1, // 높이는 칸 높이만큼만, 가로는 가운데
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: AppSizes.pageMaxWidth),
            child: SizedBox(
              height: 56,
              // 기기 글자 크기를 키워도 칸 높이(56)가 넘치지 않게 탭 글자 배율은 1.3배까지만 키운다
              child: MediaQuery.withClampedTextScaling(
                maxScaleFactor: 1.3,
                child: Row(
                  children: [
                    for (var i = 0; i < _tabs.length; i++)
                      Expanded(
                        child: _TabCell(
                          index: i,
                          tab: _tabs[i],
                          selected: i == selected,
                          unread: i == _messagesTab ? unread : 0,
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// 탭 한 칸: 아이콘 + 글자. 칸 전체가 눌리는 영역이다 (가로는 화면/5, 세로 56 — 48 이상).
class _TabCell extends StatelessWidget {
  const _TabCell({required this.index, required this.tab, required this.selected, required this.unread});

  final int index;
  final _Tab tab;
  final bool selected;
  final int unread; // 쪽지 탭만 0보다 클 수 있다

  @override
  Widget build(BuildContext context) {
    // 고른 탭은 색(accent)만이 아니라 굵은 글자 + 채운 아이콘으로도 구분한다
    final color = selected ? AppColors.accent : AppColors.muted;
    return Semantics(
      button: true,
      selected: selected, // 스크린리더에 "선택됨"을 알린다
      // 쪽지 탭에 안 읽은 쪽지가 있으면 개수까지 읽어 준다 (쪽지함 행 배지와 같은 문장)
      label: unread > 0 ? '${tab.label}, 안 읽은 쪽지 $unread개' : tab.label,
      excludeSemantics: true, // 안쪽 아이콘·글자는 위 라벨로 대신 읽는다
      // excludeSemantics가 InkWell의 누르기 동작까지 지우므로, 스크린리더로도 누를 수 있게 여기에 다시 준다
      onTap: () => context.go(tab.path),
      child: InkWell(
        key: ValueKey('bottom-tab-$index'),
        onTap: () => context.go(tab.path), // 이동은 context.go만 (뒤로 가기 기록은 앱이 따로 쌓는다)
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // 아이콘 위에 배지를 겹친다 (Stack): 아이콘은 가운데, 배지는 오른쪽 위로 살짝 튀어나온다
            Stack(
              clipBehavior: Clip.none,
              children: [
                Icon(selected ? tab.selectedIcon : tab.icon, size: 24, color: color),
                if (unread > 0)
                  Positioned(left: 14, top: -6, child: _UnreadDot(count: unread)),
              ],
            ),
            const SizedBox(height: 2),
            Text(
              tab.label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: AppSizes.fsSmall,
                color: color,
                fontWeight: selected ? FontWeight.w700 : FontWeight.w400,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// 안 읽은 쪽지 수 배지: 노랑(highlight) 바탕 + ink 글자 (노랑 위엔 ink 글자만). 100 이상은 "99+".
class _UnreadDot extends StatelessWidget {
  const _UnreadDot({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('unread-badge'), // 테스트가 배지를 찾는 이름표
      constraints: const BoxConstraints(minWidth: 18, minHeight: 18),
      padding: const EdgeInsets.symmetric(horizontal: 5),
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: AppColors.highlight,
        borderRadius: BorderRadius.circular(9),
      ),
      child: Text(
        formatUnreadBadge(count),
        maxLines: 1,
        style: const TextStyle(
          fontSize: 12,
          height: 1.2,
          fontWeight: FontWeight.w700,
          color: AppColors.ink,
        ),
      ),
    );
  }
}
