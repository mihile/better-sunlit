# Better Sunlit

선릿 밸리를 위한 개인 기능·호환 모드입니다. Minecraft 1.20.1 / Forge 47.4 / Stardew Fishing 3.7에서 사용합니다.

소스와 업데이트는 이 저장소의 **[client](https://github.com/mihile/better-sunlit/tree/client)** · **[server](https://github.com/mihile/better-sunlit/tree/server)** 브랜치로 나눕니다. 각 환경은 자기 브랜치의 서명된 업데이트만 받습니다. 두 JAR을 같은 환경에 동시에 넣지 않습니다.

<details>
<summary>낚은 물고기가 자연스럽게 펄떡입니다</summary>

기존 Sunlit Fishing Visuals 1.2.4의 연출을 포함합니다. 미니게임 성공 후 원래 보상 아이템을 유지하면서 물고기 모델을 표시합니다. 활발하게 펄떡이다가 점점 약해지고 멈추며, 약해지기 시작하면 가까이 가서 회수할 수 있습니다. 별 등급·수량·보상 NBT를 보존합니다.

`/realfishing 3 4 1`: 3초 활발함 → 4초 동안 1초 간격으로 약해짐. 설정과 저장 데이터는 기존 모드에서 이어집니다.

</details>

<details>
<summary>새 기능 설명을 펼쳐 보고 업데이트합니다</summary>

클라이언트 메인 메뉴의 **Better Sunlit**에서 변경된 기능을 짧게 확인하고, 항목을 눌러 자세한 설명을 펼칠 수 있습니다. 업데이트는 사용자가 선택했을 때 내려받고 게임 종료 후 적용합니다. 자동 확인을 끌 수도 있습니다.

서버는 시작 전에 `server` 배포를 확인합니다. 새 버전의 정상 시작이 확인되지 않으면 다음 실행에서 직전 백업을 복원하고 해당 버전을 다시 설치하지 않습니다. 통신 실패 시 설치된 버전을 유지합니다.

</details>

## 설치

각 브랜치에 대응하는 [Releases](https://github.com/mihile/better-sunlit/releases)의 `better-sunlit.jar`을 사용합니다. 클라이언트에는 `client-build-*`, 서버에는 `server-build-*` 배포를 설치합니다. 기존 `sunlit-fishing-visuals-*.jar`은 게임 종료 후 별도 보관하고 `mods`에는 한 버전만 둡니다.

낚시 연출을 보려면 서버와 클라이언트 양쪽에 설치합니다. 네트워크 프로토콜은 기존 1.2.4와 같은 `3`을 유지합니다. 기존 설정·데이터를 보존하기 위해 Forge mod ID `sunlit_fish_visuals`를 유지하며 표시 이름과 배포 파일 이름은 Better Sunlit입니다.

서버 업데이트는 배포 소스의 `scripts/Update-Server.ps1`을 서버의 `tools/better-sunlit/`에 두고, 시작 BAT에서 Minecraft 실행 전에 호출합니다. 서명 검증 키는 JAR에 포함되고 개인 서명 키는 배포하지 않습니다.

개발·배포 방법과 복원 절차는 [MAINTAINER.md](MAINTAINER.md)를 참고하세요.
