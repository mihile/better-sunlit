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

## 월드 진입 시 반복 계산 보완

`better_sunlit_entry.mixins.json`은 JEI 15.20.0.129와 SuperMartijn642 Core Lib 1.1.19의 검토된 클래스 SHA-256이 일치할 때만 클라이언트에서 적용합니다. JEI의 `ItemStackListFactory.create` 한 번을 실행하는 동안에만 CoreLib의 기본 아이템 종류 후보를 캐시합니다. 아이템 레지스트리가 잠겨 있을 때만 사용하며, 기존 탭별 표시 조건과 순서는 그대로 평가합니다. 스레드별 범위는 정상 반환·예외·중첩 호출 모두에서 해제합니다. 레시피 등록을 다른 스레드로 옮기거나 생략하지 않습니다.

JEI `TypedItemStack`의 Guava 캐시는 초기 테이블 용량만 1,048,576으로 지정합니다. 실제 설치 팩의 첫 레시피 등록에서 테이블이 이 크기까지 확장되는 것을 확인한 값이며, 기존 로더, 동시성 1, 마지막 접근 후 1초 만료 규칙은 유지합니다. 언어·리소스·레시피 목록을 월드 간에 캐시하지 않습니다. `tools/test_world_entry.py`가 실제 대상 바이너리, 필터의 순서·범위·스레드 격리, 예외 후 정리와 Guava 만료 동작을 검사합니다. 합성 성능 비교는 인게임 진입 시간 개선 수치가 아닙니다.

Create 6.0.8의 `BlockCuttingCategory.condenseRecipes`는 호출 중에만 그룹의 재료 참조를 보관합니다. 원본 `ItemHelper.matchIngredients`의 호출 방향·순서, 첫 일치 그룹 선택, 출력 객체와 순서는 유지하고 그룹을 비교할 때마다 만들어지던 `NonNullList`를 줄입니다. Iris `BlockMaterialMapping.createBlockStateIdMap`은 초기 용량만 131,072로 지정합니다. 정수 속성 0..255의 이름은 변경 불가능한 문자열 표를 재사용합니다. 정확히 vanilla `IntegerProperty`인 경우만 적용하고 사용자 정의 속성·범위 밖 정수는 원래 이름 계산을 유지합니다. 패치는 검토한 대상 클래스 해시가 맞을 때만 적용합니다.


## 성능 최적화 회귀 검사

`tests/perf/ReviewRegressionTests.java`는 검토에서 재현된 조밀한 그래프, 새 스레드의 최초 작업 배열, 동일 크기의 이모지·로그 데이터 변경, 실제 GLSL 변환기 캐시를 검사합니다. `tools/test_perf_opts.py`가 성능 검사 소스를 함께 컴파일합니다. 정렬 비용은 변경 전 간선으로 계산하고, 원본 MIN_WEIGHT의 최종 순서와 남긴 간선까지 비교합니다.

클라이언트 시작 시 버려지는 `CrashReport.preload()`의 하드웨어 조회만 생략합니다. 별도 스레드에서 `SystemReport`를 생성하면 다른 모드의 보고서 Mixin이 Forge 클래스 로더와 경합할 수 있으므로 다시 생성하지 않습니다. 실제 충돌 보고서는 호출 스레드에서 하드웨어 정보를 수집합니다. 배낭 모델 모양도 중복 상태를 줄인 뒤 호출 스레드에서 계산하여 지연 모델 로딩과 Forge 클래스 변환이 공용 워커에서 일어나지 않게 합니다.

텍스처 투명도와 밉맵 빠른 경로는 설치된 Minecraft·Emberbium·Iris 대상 클래스의 해시가 일치할 때만 적용합니다. 밉맵은 Iris의 네 픽셀 색상 계산 바이트코드가 실제 적용된 경우에만 켜고, 별도 레벨이 제공되거나 지원 범위를 벗어난 입력은 원래 구현에 맡깁니다. `tools/test_perf_opts.py`는 설치된 원본 코드를 이용해 네이티브 이미지의 투명도·밉맵 픽셀을 비교합니다. 이 CPU 검사는 인게임 화면 검증을 대신하지 않습니다.

반투명 그래프는 비순환 경로의 작업 배열을 재사용합니다. 순환 경로는 원본 알고리즘의 결과를 사용하고, 정점 객체·간선 순서·가중치·알고리즘 제공자가 모두 같은 입력만 재사용합니다. 반환 목록의 할당과 예열된 작업 경로의 할당을 구별하세요.

GLSL 캐시는 `PerfTransformPatcherMixin`이 초기화 종료 시 등록한 실제 `TransformPatcher.transformer`를 정리합니다. 편의용 `ASTParser` singleton과 혼동하지 마세요. 파서와 변환기 의존 클래스의 해시가 달라지면 등록·정리를 함께 건너뜁니다. 캐시 정리 후 보유량과 재파싱 비용은 인게임 캡처로 별도 확인합니다.

이모지 색인은 목록 양 끝뿐 아니라 중간 객체와 트리거 내용도 할당 없이 비교합니다. 변경되면 문자열 결과 캐시와 변경된 기본 Emoji의 정규식 캐시를 무효화합니다. 이 내용 검증은 CPU 비용이 있으므로 0바이트 할당을 전체 렌더링 비용 0으로 표현하지 않습니다. LogBegone도 스냅샷 내용을 비교하고 설정 이벤트에서 무효화하며, Matcher가 마지막 로그 문자열을 보유하지 않도록 초기화합니다.

서버 빌드의 클라이언트 전용 entry Mixin 제외 목록은 `better_sunlit_entry.mixins.json`에서 가져옵니다. Java 17에서는 UTF-8 인자 파일을 읽는 `javac`에 `-J-Dfile.encoding=UTF-8`을 전달해 한글 경로의 플랫폼 인코딩 오류를 방지합니다.

## 기능 설명과 게시

배치의 전체 실행 출력은 `build/release-logs/`에 저장됩니다. 게시 후 검증에서는 GitHub 원본 API의 브랜치와 게시 커밋의 고정 raw 주소를 모두 확인합니다. 브랜치 raw 주소의 CDN 캐시만 이전 버전이면 안내를 표시하며 게시 실패로 처리하지 않습니다. 실제 원본·서명·JAR 불일치는 실패합니다. 이미 게시된 빌드를 재빌드나 재게시 없이 확인하려면 `GITBOTH.BAT --verify-only`를 실행합니다.

자동 커밋 전에 `tools/publication_guard.py`가 허용된 소스·리소스·배포 파일만 포함됐는지 검사합니다. 개인 키와 알려진 인증 토큰 형식도 검사하며, push 전에는 현재 트리와 아직 공개되지 않은 커밋의 파일을 다시 검사합니다. 서명 전에는 완성된 JAR과 내부 업데이트 도우미도 검사하여 Git에서 무시된 로컬 파일이 JAR에 섞이는 경우를 차단합니다. 허용되지 않은 자료는 `build/` 또는 `local-*/`에 보관하세요. 새 파일 종류를 배포해야 한다면 허용 목록을 검토해서 추가합니다. 이 검사는 모든 종류의 비밀정보를 자동 판별한다는 보장은 아닙니다.

인스턴스 루트의 `BSGITBOTH.BAT` 또는 저장소의 `GITBOTH.BAT`로 클라이언트와 서버를 함께 배포할 수 있습니다. 각 채널의 공개 피드가 가리키는 Git 태그와 현재 JAR 빌드 입력을 비교하고, 변경된 채널만 게시합니다. 릴리스 설명·문서·배포 도구만 바뀐 경우에는 두 채널의 빌드와 게시를 건너뜁니다. 게시할 채널이 있으면 두 JAR을 병렬 빌드하고 전체 검사를 한 번 실행한 뒤, 해당 채널을 서명·게시하고 공개 JAR과 피드를 다시 내려받아 검증합니다. 빌드별 전체 출력은 `build/build-logs/`에 남깁니다. `GITCLIENT.BAT`, `GITSERVER.BAT`는 해당 채널만 게시하며 `CLIENT.BAT`는 검사 후 로컬 클라이언트에만 적용합니다. GitHub 게시 배치는 로컬 모드 폴더를 덮어쓰지 않습니다.

```powershell
python tools/release.py --side both    # 양쪽 채널
python tools/release.py --side client  # 클라이언트만
python tools/release.py --side server  # 서버만
python tools/release.py --side both --prepare-only  # 로컬 커밋·동기화·빌드·검증만, push/게시 안 함
```

배포 도구는 작업 변경을 로컬 커밋하고 원격 변경을 확인합니다. 양쪽 공통 파일은 Git 이력을 사용해 병합하고, 각 브랜치의 피드와 채널 설정은 따로 유지합니다. 공통 파일에 충돌이 있으면 덮어쓰지 않고 중단합니다. 빌드·전체 검사·양쪽 서명 사전 검증이 끝나기 전에는 소스나 릴리스를 push하지 않습니다. 동시에 두 배치를 실행하면 나중에 실행한 쪽은 중단됩니다. 한 채널 게시 후 다른 채널에서 실패하면 완료된 채널을 표시하므로 오류를 고친 뒤 다시 실행하세요.

`releases/<label>.json`의 `changes`에 해당 버전의 변경 사항과 버그 수정을 짧은 `title`과 `detail`로 적습니다. Better Sunlit 화면은 기능 소개 대신 최신 체인지로그를 표시하고, 오프라인에서는 설치 JAR의 변경 내역을 표시합니다. 기존 `features` 형식은 과거 릴리스 호환용으로 읽습니다. 새 빌드를 게시하려면 버전 번호를 올린 JSON과 해당 빌드의 `changes`를 먼저 작성합니다. 배포 도구는 앞선 릴리스 설명을 자동 복제하지 않습니다. 클라이언트 릴리스는 공개 검증을 마친 뒤 GitHub Latest로 표시합니다. 클라이언트 UI와 GitHub 릴리스가 이 설명을 사용합니다. 원래 기능을 유지한 누적 빌드로 만들고 빌드 번호는 기존 공개 배포보다 증가시킵니다. 공개 자료에는 로그·힙 덤프·JFR·사용자 설정·인증 정보·개인 키를 넣지 않습니다.

```powershell
# 검토한 소스를 해당 브랜치에 commit/push 한 뒤 실행
python tools/publish.py --side client --changes releases/20261108.2.json
```

`server` 브랜치에서도 `--side server`로 게시합니다. 게시 도구는 서명 검증, 릴리스 자산 생성, 해당 브랜치 feed 갱신, 공개 자산 재다운로드와 검증까지 수행합니다. 기존 태그나 다른 브랜치 feed를 덮어쓰지 않습니다.

## 교체와 복원

클라이언트는 다운로드 완료 후 별도의 Java 보조 프로세스가 승인한 게임 PID와 시작 시각을 확인해 종료를 기다립니다. 설치된 JAR의 해시가 승인 당시와 다르면 수동 변경을 보존하고 중단합니다. 검증된 파일만 원자적으로 교체하고 원본은 `.better-sunlit-update/backups/`에 남깁니다.

서버 시작 스크립트는 `Update-Server.ps1`이 JAR 내부 보조 프로그램을 검증해 별도 파일로 꺼낸 뒤 실행합니다. Minecraft JVM에서 사용 중인 JAR을 덮어쓰지 않습니다. 서버 시작 실패 후 다음 실행에서 원래 해시가 맞는 백업만 복원합니다. `.better-sunlit-update/blocked.properties`에 실패한 빌드를 기록합니다. 원인을 해결하고 같은 빌드를 재시도하려면 서버를 종료한 상태에서 이 차단 기록을 별도 보관한 뒤 재시도합니다.

배포 후 양쪽 공개 feed·JAR의 서명과 해시를 다시 확인합니다. 단위·보조 프로세스 테스트와 실제 인게임 확인은 따로 기록하고 미실행 항목을 통과했다고 표기하지 않습니다.

## EMF 모델 사전 베이크 호환

EMF 3.2.4 / ETF 7.1 및 EMF 3.3.12 / ETF 7.2.7을 검증합니다. `EmfLoadingContext`는 모델 로딩 상태의 실제 필드 형식(String 또는 ThreadLocal)을 시작 시 한 번 해석합니다. 일반 모델 형상 베이크는 두 버전 모두 병렬로 유지합니다. EMF 모델 생성까지 병렬로 처리하려면 ThreadLocal 형식과 실제 적용된 EMF·ETF 동시성 보완 Mixin의 표식이 모두 필요합니다. 공유 문자열을 사용하는 3.2.4에서는 원래 렌더 스레드의 EMF/Fusion 후처리를 한 번 수행하며, 원본 모델을 EMF 처리 완료 모델로 잘못 전달하지 않습니다. 미검토 EMF·ETF 바이너리에는 EntityModelSet 최적화를 적용하지 않습니다.

## 원본 팩 최적화 호환 (2026.11.18.1)

원본 Oculus 1.8.0/EMF 3.2.4/ETF 7.1/Particle Rain 4.0.0-beta.10/Colorwheel 1.2.9와 기존 팩의 바이너리 지문을 별도로 검토합니다. 기본 지문과 기존 구현은 유지합니다. `BASE_PACK_HASHES`는 검토한 원본 클래스만 추가하며, 임의 버전 허용 목록이 아닙니다.

Particle Rain 구버전은 `LegacyParticleSpawnerMixin`에서 원본 높이 범위와 조건 순서를 유지하고 높이 커서와 호출 범위 내 바이옴 조회를 재사용합니다. 스코프는 정상 반환·예외·중첩 호출에서 복원하며 월드 참조를 보유하지 않습니다. 구버전 전용 입자 틱/렌더 코드는 원본의 `opts`, 개별 필드, 화면 크기 옵션과 바람/페이드/충돌 순서를 유지합니다. 현대 버전의 `WindManager`나 존재하지 않는 높이 유틸리티를 호출하지 않습니다. MixinExtras API가 없으면 스코프 최적화를 허용하지 않습니다. 빌드 시 설치된 중첩 라이브러리에서 API만 추출하며 배포 JAR에는 중복 포함하지 않습니다.

구버전 EMF 자식 배열은 같은 맵에서 같은 크기로 자식을 교체해도 참조를 갱신합니다. 기존 팩의 구현은 별도로 유지합니다. Colorwheel 구버전은 `gbufferType` 없는 메서드에 전용 주입을 사용하고 버전별 캐시 키를 구분합니다.

OBE 미설치로 생기는 두 앵커 경로와 구버전 Embeddium에 존재하지 않는 `embeddium$processTransparentImages` 경로는 해당 기능 없음으로 제외합니다. `tools/test_base_optimizations.py`는 실제 원본 JAR, 기존 팩 적용 조건, 변조된 대상·제거한 의존성의 차단과 스코프/자식 맵 회귀를 검사합니다. 런타임 적용 및 서버/날씨/셰이더 검증 결과는 로컬 `build/base-audit/`에 보관합니다.

Iris·Colorwheel 디스크 캐시 키에는 실제 변환기 클래스의 SHA-256을 포함합니다. 같은 인스턴스에서 모드 버전을 바꿔도 다른 변환기의 산출물을 재사용하지 않으며, 지문을 읽을 수 없으면 해당 디스크 캐시를 사용하지 않습니다. 기존 캐시 파일 형식은 유지하지만 새 키 이름 공간을 사용합니다.

## Oculus·Embeddium 렌더링 보완 (2026.11.18.2)

실제 설치한 Emberbium 0.3.32-beta.118 / Emberlens 1.8.9와 원본 Embeddium 0.3.31 / Oculus 1.8.0의 코드를 비교하여 원본의 대형 정점 정렬, uniform·sampler 목록 순회와 sampler 초기화 범위를 보완합니다. 네 전용 Mixin은 원본 클래스의 정확한 지문을 검사하며 이미 해당 개선이 있는 Ember 계열에는 적용하지 않습니다. 기존 GLSL·그래프·밉맵 최적화는 유지합니다.

정점 256개 이상에 안정적인 내림차순 기수 정렬을 사용하고 작은 입력과 NaN은 원래 MergeSort를 호출합니다. 기존 숫자 비교에서 같은 값인 +0/-0을 같은 키로 처리하여 동거리 정점 순서를 보존합니다. 스레드별 정수 작업 배열은 최대 65,536개까지만 보유하고 반환 배열을 재사용하지 않습니다. uniform·sampler 순회는 갱신 순서, 최초 uniform 설정, active texture 복원과 listener 해제를 유지합니다.

sampler 바인딩의 최대 사용 위치를 추적하여 빈 초기화를 생략합니다. multibind에서는 OpenGL의 null 포인터로 범위 내 바인딩을 0으로 지정하고, 미지원 환경에서는 기존 개별 바인딩 해제를 사용합니다. 렌더러 초기화 때 추적 상태를 초기화합니다. 무관한 외부 GL 상태를 임의로 캐시하지 않습니다.

`RendererContracts`는 실제 원본 MergeSort와 무작위·동거리·0·무한대·NaN 입력, 반환 배열 독립성과 작업 스레드 격리를 비교합니다. sampler 패치의 실제 바이트코드에 기록용 GL 함수를 연결하여 빈/반복/최대 범위 및 두 GL 경로를 검사하고 uniform 호출 순서와 빈 목록도 확인합니다. `test_base_optimizations.py`에서 실행합니다. 게임 검증은 실제 OpenGL sampler 상태를 조회하고 복원하며 두 팩의 서버 접속·주입 표식을 확인합니다. 결과와 비교 자료는 로컬 `build/renderer-audit/`에 보관합니다. 기수 정렬 CPU 시간은 FPS 개선 수치가 아닙니다.

## 첫 접속 반복 계산 보완 (2026.11.18.3)

JEI 15.20.0.129의 일반 `NormalizedTypedItem`에 한해 원래 `RecipeMap.getIngredientUid`가 반환하는 등록 Item을 임시 ItemStack 없이 조회합니다. NormalizedTypedItem·ItemStackHelper·SubtypeManager·SubtypeInterpreters·RecipeMap·VanillaTypes·VanillaTypes$1·TypedItemStack·Minecraft ItemStack의 검토된 지문을 모두 검사합니다. 접근 Mixin의 표식이 하나라도 없거나 사용자 정의 helper/manager/registry라면 원래 경로를 유지합니다. NBT를 가진 정규화 항목과 하위 유형 해석이 필요한 아이템도 원래 경로를 사용합니다. 하위 유형 결과를 캐시하지 않고 현재 interpreter 맵을 매번 확인하므로 런타임 등록을 반영합니다.

Create 6.0.8 석재 절단 그룹은 검토한 ItemHelper와 vanilla Recipe/Ingredient 바이너리에서만 후보 색인을 사용합니다. 배열 길이와 첫 아이템의 동일성은 필요 조건으로만 사용하며 최종 판정은 원래 ItemHelper를 호출합니다. 같은 키와 사용자 정의 재료 그룹을 생성 순서대로 병합해 첫 일치 그룹·호출 방향·출력 순서를 유지합니다. 사용자 정의 Recipe는 기존 순회를 유지하며, 재료 참조·후보 색인은 해당 호출 안에서만 보유합니다.

GLSL 키는 길이 접두사와 UTF-8 바이트를 그대로 유지하고 큰 문자열을 제한된 작업 버퍼로 해시합니다. ASCII 복사 전 전체 입력을 확인하고, 유니코드 청크 경계의 surrogate 쌍을 분리하지 않습니다. 작은 문자열과 잘못된 surrogate의 대체 바이트는 기존 String.getBytes(UTF_8)와 일치합니다. 큰 원본 문자열이나 필드의 완성된 UTF-8 배열을 스레드 캐시에 보유하지 않습니다. 키 이름 공간과 저장 형식이 같으므로 기존 디스크 캐시를 계속 읽습니다.

검토한 Embeddium 0.3.31 / Emberbium 0.3.32-beta.118의 RenderSectionManager는 시야 거리와 월드 섹션 수를 이용해 섹션 인덱스의 초기 용량만 정합니다. 예상 항목 수는 256..65,536으로 제한하고 시야 거리·로드 범위·키/값 동작은 유지합니다. fastutil은 초기 테이블 크기를 축소 하한으로 유지하므로 섹션 수가 줄어도 기본 생성자보다 큰 테이블을 보유할 수 있습니다. 새 지문에는 적용하지 않습니다.

`Utf8DigestContracts`는 독립적인 JDK UTF-8/해시 결과, 청크 경계·잘못된 surrogate·스레드 격리 및 섹션 맵의 확장/조회/삭제를 검사합니다. `PlainUidContracts`는 실제 JEI 클래스에 접근 메서드를 연결한 CPU fixture로 fast-path와 사용자 정의 도우미·실시간 subtype 변경을 검사합니다. CPU fixture는 게임 레지스트리 부팅을 요구하지 않도록 해당 조회와 무관한 초기화만 격리합니다. 실제 게임의 전체 JEI 등록 항목, 실제 RecipeMap UID 조회, 출력 그룹과 NBT 비교는 별도의 로컬 검증 에이전트로 확인합니다.

첫 접속 JFR·새 JVM 반복 실행·실제 주입 및 원본 팩 검증 자료는 `build/join-20261118/`에 보관합니다. 프로파일 기록은 접속 후 50초이며 데이터 검증 에이전트는 기록 종료 후 실행합니다. 인증 서버 오류로 접속하지 못한 실행과 타이밍 계측 에이전트를 사용한 진단 실행은 성능 비교에서 제외합니다. 버퍼 할당량과 등록 단계 감소를 전체 FPS 개선으로 표현하지 않습니다. 효과 없는 소스 해시 prefix 재사용과 초기 조명 대기열 분산 실험은 제품 코드에서 제외하고 로컬 자료에만 보존합니다.
