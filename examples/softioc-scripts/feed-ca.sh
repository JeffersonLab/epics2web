#!/bin/sh

i=0

while [ true ]
do
  i=$((i + 1))
  caput $1 $i
  sleep 1
done