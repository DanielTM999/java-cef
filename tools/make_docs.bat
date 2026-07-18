@echo off
:: Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

set RETURNCODE=0
setlocal

pushd "%~dp0\..\java"

set OUT_PATH="..\out\docs"
set CLS_PATH="..\third_party\jogamp\jar\*"

if not exist %OUT_PATH% mkdir %OUT_PATH%
javadoc --ignore-source-errors -Xdoclint:none -windowtitle "CEF3 Java API Docs" -footer "<center><a href="https://github.com/chromiumembedded/java-cef" target="_top">Chromium Embedded Framework (CEF)</a> Copyright &copy 2013 Marshall A. Greenblatt</center>" -nodeprecated -d %OUT_PATH% -classpath %CLS_PATH% -sourcepath . org.cef org.cef.browser org.cef.callback org.cef.handler org.cef.misc org.cef.network
if errorlevel 1 set RETURNCODE=1

:end
popd
endlocal & exit /B %RETURNCODE%
