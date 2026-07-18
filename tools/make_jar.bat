@echo off
:: Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

set RETURNCODE=0
setlocal

if "%1" == "" (
echo ERROR: Please specify a build target: win32 or win64
set RETURNCODE=1
goto end
)
pushd "%~dp0\..\out\%1"
jar -cmf manifest\MANIFEST.MF jcef.jar -C . org
if errorlevel 1 (
set RETURNCODE=1
goto end
)
jar -cf jcef-tests.jar -C . tests
if errorlevel 1 (
set RETURNCODE=1
goto end
)

:end
if "%1" == "" goto after_popd
popd
:after_popd
endlocal & exit /B %RETURNCODE%
