#!/bin/bash

# Specify the output directory
OUTPUT_DIR=~/bitbucket/Attack-the-BLOCC/simulations

# Create the output directory if it does not exist
mkdir -p "$OUTPUT_DIR"

# Change to the appropriate directory
cd ~/bitbucket/Attack-the-BLOCC/tools/cooja

# Specify the log file path
LOGFILE="$OUTPUT_DIR/output.txt"

# Run the gradle command and redirect both stdout and stderr to the log file while also displaying it on the terminal
./gradlew run --args='--no-gui ../../simulations/java_10x10x10_1_sim.csc' 2>&1 | tee "$LOGFILE"
