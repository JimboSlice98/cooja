<<<<<<< HEAD
TIMEOUT(650000);
=======
TIMEOUT(200000);
>>>>>>> single-channel-no-bls-chaining

function formatTime(microseconds) {
    let milliseconds = Math.floor(microseconds / 1000);
    
    let mins = Math.floor(milliseconds / 60000);
    let secs = Math.floor((milliseconds % 60000) / 1000);
    let millis = milliseconds % 1000;
    
    function padZero(value, length) {
        let strValue = value.toString();
        while (strValue.length < length) {
            strValue = '0' + strValue;
        }
        return strValue;
    }
    
    let paddedSecs = padZero(secs, 2);
    let paddedMillis = padZero(millis, 3);
    
    return mins + ':' + paddedSecs + '.' + paddedMillis;
}

var simulationTitle;
try {
    simulationTitle = sim.getTitle();
} catch (e) {
    simulationTitle = "default_simulation";
}

var logFilePath = "../../simulations/" + simulationTitle + "_log.txt";
var logFile = new java.io.FileWriter(logFilePath);

while (true) {
    let logMessage = formatTime(time) + "  ID:" + id + "  " + msg + "\n";
    log.log(logMessage);
    logFile.write(logMessage);
    logFile.flush();
    YIELD();
}
