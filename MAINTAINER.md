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

## 기능 설명과 게시

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
