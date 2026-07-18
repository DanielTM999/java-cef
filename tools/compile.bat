@echo off
:: Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

set RETURNCODE=0
setlocal
pushd "%~dp0\.."

if "%1" == "" (
echo ERROR: Please specify a target platform: win32 or win64
set RETURNCODE=1
goto end
)

set OUT_PATH=".\out\%1"
set CLS_PATH=".\third_party\jogamp\jar\*;.\third_party\junit\*;.\java"
set "SRC_LIST=%TEMP%\jcef_sources_%RANDOM%.txt"

if not exist %OUT_PATH% mkdir %OUT_PATH%
if exist "%SRC_LIST%" del /f /q "%SRC_LIST%"
(
  for %%D in (
    java\tests\detailed
    java\tests\junittests
    java\tests\orion
    java\tests\simple
    java\org\cef
    java\org\cef\browser
    java\org\cef\callback
    java\org\cef\handler
    java\org\cef\misc
    java\org\cef\network
  ) do (
    for %%F in (%%D\*.java) do echo %%F
  )
) > "%SRC_LIST%"
javac -Xdiags:verbose -cp %CLS_PATH% -d %OUT_PATH% @"%SRC_LIST%"
if exist "%SRC_LIST%" del /f /q "%SRC_LIST%"
if errorlevel 1 (
set RETURNCODE=1
goto end
)

:: Copy MANIFEST.MF
if not exist %OUT_PATH%\manifest mkdir %OUT_PATH%\manifest
xcopy /sfy .\java\manifest %OUT_PATH%\manifest\

:: Copy resource files.
if not exist %OUT_PATH%\tests\detailed\handler mkdir %OUT_PATH%\tests\detailed\handler
xcopy /sfy .\java\tests\detailed\handler\*.html %OUT_PATH%\tests\detailed\handler\
xcopy /sfy .\java\tests\detailed\handler\*.png %OUT_PATH%\tests\detailed\handler\

:end
popd
endlocal & set RETURNCODE=%RETURNCODE%
goto omega

:returncode
exit /B %RETURNCODE%

:omega
call :returncode %RETURNCODE%
