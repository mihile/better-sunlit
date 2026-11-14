# 유지보수와 배포

## 브랜치

하나의 저장소에서 `client`와 `server` 브랜치를 사용합니다. 브랜치마다 `release-channel.properties`의 `channel`이 다릅니다. 공통 낚시 로직 변경은 양쪽에 반영하고 패킷 프로토콜을 함께 검토합니다. 서버 산출물에는 클라이언트 전용 클래스와 클라이언트 Mixin을 포함하지 않습니다.

업데이트 주소는 `https://raw.githubusercontent.com/mihile/better-sunlit/<client|server>/feed/update.properties`입니다. GitHub의 저장소 전체 `latest` 릴리스에 의존하지 않습니다. 릴리스 태그는 `client-build-2026.11.08.1`, `server-build-2026.11.08.1` 형식입니다.

## 빌드와 테스트

Java 17, Python 3, Git, GitHub CLI, 설치된 선릿 밸리 Prism 인스턴스가 필요합니다. Forge SRG 라이브러리와 Stardew Fishing을 포함한 실제 모드 JAR은 공개 저장소에 넣지 않습니다.

```powershell
python build.py --instance 'C:/path/to/PrismLauncher/instances/Sunlit' --side client --build 2026110802 --label 2026.11.08.2
python build.py --instance 'C:/path/to/PrismLauncher/instances/Sunlit' --side server --build 2026110802 --label 2026.11.08.2
python tools/test.py
```

출력은 `build/<side>/better-sunlit.jar`입니다. `--java-home`으로 JDK를 지정할 수 있습니다. 서명 키는 기본적으로 사용자 홈의 `.codex/secrets/better-sunlit/<side>.pk8`에 만들며 공개 키만 `keys/`에 보관합니다. 공개 키가 있는데 개인 키가 없어졌다면 새 키를 만들지 말고 원래 키를 복구하세요.

`tools/test.py`는 설치된 ModernUI를 사용한 글꼴 CPU 테스트도 실행합니다. 제어 문자의 일반·굵은 글씨 폭과 이모지·리소스팩 글꼴의 우선순위를 검사하며 게임이나 그래픽 컨텍스트는 실행하지 않습니다. 실제 툴팁·이모지 화면 확인은 별도 인게임 검증입니다.

`tools/test.py`와 `tools/test_perf_opts.py`는 이미 만들어진 두 JAR을 검사하며 다시 빌드하지 않습니다. 전체 검사 전후에 JAR·업데이트 도우미·빌드 보고서가 그대로인지 확인합니다. 독립적인 검사는 최대 3개씩 병렬로 실행하고 전체 출력은 `build/test-logs/`에 남깁니다. 따라서 직접 실행할 때도 먼저 두 채널을 동일한 빌드 번호로 빌드해야 합니다.

모드의 한국어 보완 파일은 `src/main/resources/assets/<namespace>/lang/ko_kr.json`에 누적합니다. 로컬 적용 파일은 인스턴스의 `minecraft/kubejs/assets/<namespace>/lang/ko_kr.json`에도 병합합니다. 기존 한국어와 이리듐 등 모드팩 명칭을 덮어쓰지 말고, 같은 키가 다른 namespace에도 있으면 영어 값이 다시 덮어쓰지 않는지 확인합니다. 치환 인자의 의미·순서, 색상 코드, 명령어 예시와 파일명을 보존합니다. 로컬 상세 점검 및 복원 목록은 무시되는 `build/localization-all/`와 `local-backups/`에 남깁니다.

## 코드 및 스크립트의 한국어 보완

코드에 직접 적힌 문구는 `assets/better_sunlit/inline/rules.json`의 검토된 호출 위치에서만 번역 컴포넌트로 바꿉니다. 메서드 명령어 지문이 달라지면 원문을 유지합니다. 다른 모드가 주입한 문구는 원본 Mixin 클래스 해시와 병합된 메서드의 텍스트 지문도 검사합니다. 별도 선택적 Mixin 설정을 사용하며 기존 낚시·글꼴 Mixin의 필수 적용 규칙은 유지합니다. 영어 fallback을 포함하여 서버의 `getString()` 결과를 보존합니다. 임의 채팅·사용자 입력에는 전역 치환을 하지 않습니다.

KubeJS의 네 안내문은 `inline/scripts.json`의 전체 소스 해시와 문자열 범위가 맞을 때만 메모리에서 `Text.translatable`로 바꿉니다. 디스크의 스크립트 로직은 수정하지 않습니다. 외부 Patchouli 도감 보완 원본은 `localization/pack-files/`에 보관합니다. `python tools/apply_localization.py --instance <경로>`는 한국어 자산과 해당 도감 파일을 독립 백업한 뒤 로컬에 병합합니다. 게임이 종료된 상태에서 실행하세요.

`tools/test_inline_localization.py`는 실제 설치된 클라이언트·서버 클래스에 대한 변환, 스택 검증, 변경된 버전의 적용 차단, 사용자 인자·서버 영어 fallback 보존, 언어 전환 및 스크립트 지문을 검사합니다. Forge·게임·서버·그래픽 컨텍스트를 실행하지 않으므로 실제 화면 검증과 구분합니다.

## 월드 진입 시 반복 계산 보완

`better_sunlit_entry.mixins.json`은 JEI 15.20.0.129와 SuperMartijn642 Core Lib 1.1.19의 검토된 클래스 SHA-256이 일치할 때만 클라이언트에서 적용합니다. JEI의 `ItemStackListFactory.create` 한 번을 실행하는 동안에만 CoreLib의 기본 아이템 종류 후보를 캐시합니다. 아이템 레지스트리가 잠겨 있을 때만 사용하며, 기존 탭별 표시 조건과 순서는 그대로 평가합니다. 스레드별 범위는 정상 반환·예외·중첩 호출 모두에서 해제합니다. 레시피 등록을 다른 스레드로 옮기거나 생략하지 않습니다.

JEI `TypedItemStack`의 Guava 캐시는 초기 테이블 용량만 32,768로 지정합니다. 기존 로더, 동시성 1, 마지막 접근 후 1초 만료 규칙은 유지합니다. 언어·리소스·레시피 목록을 월드 간에 캐시하지 않습니다. `tools/test_world_entry.py`가 실제 대상 바이너리, 필터의 순서·범위·스레드 격리, 예외 후 정리와 Guava 만료 동작을 검사합니다. 합성 성능 비교는 인게임 진입 시간 개선 수치가 아닙니다.

클라이언트 빌드에서 선택적 번역 Mixin의 대상 목록을 현재 클래스패스에 존재하는 클래스로 제한합니다. 비활성화한 모드의 클래스가 다중 대상 Mixin에 남아 클래스 로딩 때마다 조회 실패를 반복하지 않게 합니다. 모드 재활성화 후 해당 하드코딩 문구 보완까지 복원하려면 로컬 빌드를 다시 실행합니다.

## 성능 최적화 회귀 검사

`tests/perf/ReviewRegressionTests.java`는 검토에서 재현된 조밀한 그래프, 새 스레드의 최초 작업 배열, 동일 크기의 이모지·로그 데이터 변경, 실제 GLSL 변환기 캐시를 검사합니다. `tools/test_perf_opts.py`가 성능 검사 소스를 함께 컴파일합니다. 정렬 비용은 변경 전 간선으로 계산하고, 원본 MIN_WEIGHT의 최종 순서와 남긴 간선까지 비교합니다.

반투명 그래프는 비순환 경로의 작업 배열을 재사용합니다. 순환 경로는 원본 알고리즘의 결과를 사용하고, 정점 객체·간선 순서·가중치·알고리즘 제공자가 모두 같은 입력만 재사용합니다. 반환 목록의 할당과 예열된 작업 경로의 할당을 구별하세요.

GLSL 캐시는 `PerfTransformPatcherMixin`이 초기화 종료 시 등록한 실제 `TransformPatcher.transformer`를 정리합니다. 편의용 `ASTParser` singleton과 혼동하지 마세요. 파서와 변환기 의존 클래스의 해시가 달라지면 등록·정리를 함께 건너뜁니다. 캐시 정리 후 보유량과 재파싱 비용은 인게임 캡처로 별도 확인합니다.

이모지 색인은 목록 양 끝뿐 아니라 중간 객체와 트리거 내용도 할당 없이 비교합니다. 변경되면 문자열 결과 캐시와 변경된 기본 Emoji의 정규식 캐시를 무효화합니다. 이 내용 검증은 CPU 비용이 있으므로 0바이트 할당을 전체 렌더링 비용 0으로 표현하지 않습니다. LogBegone도 스냅샷 내용을 비교하고 설정 이벤트에서 무효화하며, Matcher가 마지막 로그 문자열을 보유하지 않도록 초기화합니다.

서버 빌드의 클라이언트 전용 entry Mixin 제외 목록은 `better_sunlit_entry.mixins.json`에서 가져옵니다. Java 17에서는 UTF-8 인자 파일을 읽는 `javac`에 `-J-Dfile.encoding=UTF-8`을 전달해 한글 경로의 플랫폼 인코딩 오류를 방지합니다.

## 기능 설명과 게시

배치의 전체 실행 출력은 `build/release-logs/`에 저장됩니다. 게시 후 검증에서는 GitHub 원본 API의 브랜치와 게시 커밋의 고정 raw 주소를 모두 확인합니다. 브랜치 raw 주소의 CDN 캐시만 이전 버전이면 안내를 표시하며 게시 실패로 처리하지 않습니다. 실제 원본·서명·JAR 불일치는 실패합니다. 이미 게시된 빌드를 재빌드나 재게시 없이 확인하려면 `GITBOTH.BAT --verify-only`를 실행합니다.

자동 커밋 전에 `tools/publication_guard.py`가 허용된 소스·리소스·배포 파일만 포함됐는지 검사합니다. 개인 키와 알려진 인증 토큰 형식도 검사하며, push 전에는 현재 트리와 아직 공개되지 않은 커밋의 파일을 다시 검사합니다. 서명 전에는 완성된 JAR과 내부 업데이트 도우미도 검사하여 Git에서 무시된 로컬 파일이 JAR에 섞이는 경우를 차단합니다. 허용되지 않은 자료는 `build/` 또는 `local-*/`에 보관하세요. 새 파일 종류를 배포해야 한다면 허용 목록을 검토해서 추가합니다. 이 검사는 모든 종류의 비밀정보를 자동 판별한다는 보장은 아닙니다.

인스턴스 루트 또는 저장소의 `GITBOTH.BAT` 한 번으로 클라이언트와 서버를 함께 배포할 수 있습니다. 두 JAR을 각각 한 번씩 병렬 빌드하고 전체 검사를 한 번 실행한 뒤, 각 채널을 서명·게시하고 공개 JAR과 피드를 다시 내려받아 검증합니다. 빌드별 전체 출력은 `build/build-logs/`에 남깁니다. `GITCLIENT.BAT`, `GITSERVER.BAT`는 해당 채널만 게시하며 `CLIENT.BAT`는 검사 후 로컬 클라이언트에만 적용합니다. GitHub 게시 배치는 로컬 모드 폴더를 덮어쓰지 않습니다.

```powershell
python tools/release.py --side both    # 양쪽 채널
python tools/release.py --side client  # 클라이언트만
python tools/release.py --side server  # 서버만
python tools/release.py --side both --prepare-only  # 로컬 커밋·동기화·빌드·검증만, push/게시 안 함
```

배포 도구는 작업 변경을 로컬 커밋하고 원격 변경을 확인합니다. 양쪽 공통 파일은 Git 이력을 사용해 병합하고, 각 브랜치의 피드와 채널 설정은 따로 유지합니다. 공통 파일에 충돌이 있으면 덮어쓰지 않고 중단합니다. 빌드·전체 검사·양쪽 서명 사전 검증이 끝나기 전에는 소스나 릴리스를 push하지 않습니다. 동시에 두 배치를 실행하면 나중에 실행한 쪽은 중단됩니다. 한 채널 게시 후 다른 채널에서 실패하면 완료된 채널을 표시하므로 오류를 고친 뒤 다시 실행하세요.

`releases/<label>.json`의 `features`에 짧은 `title`과 펼쳤을 때 보이는 `detail`을 적습니다. 클라이언트 UI와 GitHub 릴리스가 이 설명을 사용합니다. 원래 기능을 유지한 누적 빌드로 만들고 빌드 번호는 기존 공개 배포보다 증가시킵니다. 공개 자료에는 로그·힙 덤프·JFR·사용자 설정·인증 정보·개인 키를 넣지 않습니다.

```powershell
# 검토한 소스를 해당 브랜치에 commit/push 한 뒤 실행
python tools/publish.py --side client --changes releases/20261108.2.json
```

`server` 브랜치에서도 `--side server`로 게시합니다. 게시 도구는 서명 검증, 릴리스 자산 생성, 해당 브랜치 feed 갱신, 공개 자산 재다운로드와 검증까지 수행합니다. 기존 태그나 다른 브랜치 feed를 덮어쓰지 않습니다.

## 교체와 복원

클라이언트는 다운로드 완료 후 별도의 Java 보조 프로세스가 승인한 게임 PID와 시작 시각을 확인해 종료를 기다립니다. 설치된 JAR의 해시가 승인 당시와 다르면 수동 변경을 보존하고 중단합니다. 검증된 파일만 원자적으로 교체하고 원본은 `.better-sunlit-update/backups/`에 남깁니다.

서버 시작 스크립트는 `Update-Server.ps1`이 JAR 내부 보조 프로그램을 검증해 별도 파일로 꺼낸 뒤 실행합니다. Minecraft JVM에서 사용 중인 JAR을 덮어쓰지 않습니다. 서버 시작 실패 후 다음 실행에서 원래 해시가 맞는 백업만 복원합니다. `.better-sunlit-update/blocked.properties`에 실패한 빌드를 기록합니다. 원인을 해결하고 같은 빌드를 재시도하려면 서버를 종료한 상태에서 이 차단 기록을 별도 보관한 뒤 재시도합니다.

배포 후 양쪽 공개 feed·JAR의 서명과 해시를 다시 확인합니다. 단위·보조 프로세스 테스트와 실제 인게임 확인은 따로 기록하고 미실행 항목을 통과했다고 표기하지 않습니다.
