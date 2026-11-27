@echo off
chcp 65001 >nul
title URL Shortener
pushd "%~dp0"
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar target\url-shortener-1.0.0.jar
popd
pause >nul
