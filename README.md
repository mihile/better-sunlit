# Better Sunlit

선릿 밸리를 위한 개인 기능·호환 모드입니다. Minecraft 1.20.1 / Forge 47.4 / Stardew Fishing 3.7에서 사용합니다.

소스와 업데이트는 이 저장소의 **[client](https://github.com/mihile/better-sunlit/tree/client)** · **[server](https://github.com/mihile/better-sunlit/tree/server)** 브랜치로 나눕니다. 각 환경은 자기 브랜치의 서명된 업데이트만 받습니다. 두 JAR을 같은 환경에 동시에 넣지 않습니다.

<details>
<summary>낚은 물고기가 자연스럽게 펄떡입니다</summary>

기존 Sunlit Fishing Visuals 1.2.4의 연출을 포함합니다. 미니게임 성공 후 원래 보상 아이템을 유지하면서 물고기 모델을 표시합니다. 활발하게 펄떡이다가 점점 약해지고 멈추며, 약해지기 시작하면 가까이 가서 회수할 수 있습니다. 별 등급·수량·보상 NBT를 보존합니다.

`/realfishing 3 4 1`: 3초 활발함 → 4초 동안 1초 간격으로 약해짐. 설정과 저장 데이터는 기존 모드에서 이어집니다.

`/realfishing version 1`은 물고기 전체가 기울고 흔들리는 연출이며 기본값입니다. `/realfishing version 2`는 낚은 물고기의 몸을 머리부터 꼬리까지 네 구간으로 나눠 순서대로 옆으로 휘게 합니다. 두 버전 모두 낚여 나온 물고기에는 평상시 수영 동작을 적용하지 않습니다. 변경은 다음에 낚는 물고기부터 적용되며, `/realfishing`으로 현재 설정을 확인할 수 있습니다.

`/realfishing off`로 연출을 끄면 다음에 낚는 물고기부터 Stardew Fishing의 원래 보상 동작을 사용합니다. `/realfishing on`으로 켜면 저장해 둔 버전과 시간 설정으로 연출을 재개합니다. 기본값은 켜짐입니다.

Stardew Fishing의 `/stardew_fishing toggle_minigame`으로 본인의 미니게임을 건너뛰어도 낚은 물고기에 펄떡임이 적용됩니다. 물고기 연출은 `/realfishing on`과 `/realfishing off`로 따로 제어합니다.

</details>

<details>
<summary>새 기능 설명을 펼쳐 보고 업데이트합니다</summary>

새 업데이트가 있으면 클라이언트 메인 메뉴의 **Better Sunlit** 버튼에 빨간 느낌표가 표시됩니다. **업데이트 팝업 자동 열기**가 켜져 있으면 같은 창이 게임 실행 중 버전마다 한 번 자동으로 열립니다. 창 안의 체크박스로 자동 열기를 끄고, **자동 확인** 버튼으로 시작할 때 업데이트를 조회할지 선택할 수 있습니다. **업데이트**를 누르면 내려받으며 설치는 게임 종료 후 진행됩니다.

서버는 시작 전에 `server` 배포를 확인합니다. 새 버전의 정상 시작이 확인되지 않으면 다음 실행에서 직전 백업을 복원하고 해당 버전을 다시 설치하지 않습니다. 통신 실패 시 설치된 버전을 유지합니다.

</details>

## 설치

- **클라이언트**는 [최신 클라이언트 릴리스](https://github.com/mihile/better-sunlit/releases/latest)에서 `better-sunlit.jar`을 받습니다.
- **전용 서버**는 [서버 릴리스 목록](https://github.com/mihile/better-sunlit/releases?q=server-build-&expanded=true)에서 최신 `server-build-*`의 `better-sunlit.jar`을 받습니다.
- 각 릴리스의 `SHA256SUMS.txt`에서 다운로드 파일의 해시를 확인할 수 있습니다. 기존 `sunlit-fishing-visuals-*.jar`은 게임 종료 후 별도 보관하고 `mods`에는 한 버전만 둡니다.

Minecraft와 선릿밸리의 비공식 애드온입니다. 이 저장소에는 애드온만 있으며, 필요한 게임·모드팩·의존 모드는 각 공식 배포처에서 설치합니다. 클라이언트는 자동 업데이트 확인을 끌 수 있고, 다운로드와 교체는 사용자가 선택합니다.

낚시 연출을 보려면 서버와 클라이언트 양쪽에 설치합니다. 네트워크 프로토콜은 기존 1.2.4와 같은 `3`을 유지합니다. 기존 설정·데이터를 보존하기 위해 Forge mod ID `sunlit_fish_visuals`를 유지하며 표시 이름과 배포 파일 이름은 Better Sunlit입니다.

서버 업데이트는 배포 소스의 `scripts/Update-Server.ps1`을 서버의 `tools/better-sunlit/`에 두고, 시작 BAT에서 Minecraft 실행 전에 호출합니다. 서명 검증 키는 JAR에 포함되고 개인 서명 키는 배포하지 않습니다.

## 라이선스

Better Sunlit의 자체 코드는 [MIT 라이선스](LICENSE)를 따릅니다. 외부 구성 요소의 라이선스는 [외부 라이브러리 고지](THIRD_PARTY_NOTICES.txt)를 참고하세요.

개발·배포 방법과 복원 절차는 [MAINTAINER.md](MAINTAINER.md)를 참고하세요.
