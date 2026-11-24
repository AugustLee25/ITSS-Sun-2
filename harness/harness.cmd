@echo off
rem Chay harness nghiem thu Solution 2. Vi du:
rem   harness.cmd                                   (day du: static + BE + FE)
rem   harness.cmd -Stages static,fe                 (nhanh, chi FE + kiem tra tinh)
rem   harness.cmd -Stages static,be -BeTests Solution2AcceptanceTest
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-harness.ps1" %*
