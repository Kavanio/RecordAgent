@echo off
chcp 65001 >nul
setlocal EnableExtensions DisableDelayedExpansion
rem Keep CMD comments ASCII-only; save this file as UTF-8 without BOM, with CRLF.
rem Copy beside Compose files, .env and user-data. Does not repair model config.
pushd "%~dp0" || exit /b 1
set "BUTVAN_IMAGE_TAG=develop"
set "COMPOSE_FILE="
set "COMPOSE_COUNT=0"
for %%F in (compose.yaml compose.yml docker-compose.yaml docker-compose.yml) do if exist "%%F" (
    set "COMPOSE_FILE=%%F"
    set /a COMPOSE_COUNT+=1 >nul
)
if not "%COMPOSE_COUNT%"=="1" (
    echo 错误：请将本脚本放到部署目录，且只保留一个主 Compose 文件。
    goto failed
)
for %%F in (compose.acr.yaml .env user-data) do if not exist "%%F" (
    echo 错误：缺少 %%F，未执行更新。
    goto failed
)
set "DC=docker compose -f "%COMPOSE_FILE%" -f "compose.acr.yaml""
echo [1/6] 检查 Docker 和 Compose 配置
 echo 使用主配置：%COMPOSE_FILE%，覆盖配置：compose.acr.yaml
 docker info >nul 2>&1
if errorlevel 1 goto failed
%DC% config --quiet
if errorlevel 1 goto failed

echo [2/6] 强制拉取远端最新 develop 镜像；失败时不停止现有容器
%DC% pull --policy always backend
if errorlevel 1 (
    echo 拉取失败。请检查网络和 Docker 仓库登录状态，再重试。
    goto failed
)

rem Prepare a unique backup directory before stopping the service.
set "BACKUP="
for /f "delims=" %%T in ('powershell.exe -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss-fff"') do set "BACKUP=user-data-backup-%%T"
if not defined BACKUP goto failed
if exist "%BACKUP%" goto failed

echo [3/6] 停止后端以备份数据
%DC% stop backend
if errorlevel 1 goto failed

echo [4/6] 备份 user-data 到 %BACKUP%
robocopy "user-data" "%BACKUP%" /E /COPY:DAT /R:2 /W:2 /NFL /NDL /NJH /NJS
if errorlevel 8 (
    echo 备份失败，后端已停止；请排查磁盘空间或权限后重新运行。
    goto failed
)

echo [5/6] 使用已拉取镜像重建容器，并等待健康检查
%DC% up -d --no-build --pull never --force-recreate --wait --wait-timeout 240 backend
if errorlevel 1 (
    echo 容器已尝试启动，但未通过健康检查；这不等同于镜像拉取失败。
    echo 备份位于 %BACKUP%，请勿删除 user-data。
    echo 请先查看下面命令的最新日志，不要反复更新或关闭健康检查。
    echo 若日志仍提示缺少 API 地址，需修复 user-data 中的模型配置。
    echo 可执行以下命令查看日志；分享前请隐藏密钥和个人信息：
    echo docker compose -f "%COMPOSE_FILE%" -f compose.acr.yaml logs --tail 60 backend
    goto failed
)
echo [6/6] 更新完成，后端已通过就绪检查。备份：%BACKUP%
popd
pause
exit /b 0

:failed
echo 更新失败，请根据上方错误排查；本脚本不会自动回滚或删除数据。
popd
pause
exit /b 1
