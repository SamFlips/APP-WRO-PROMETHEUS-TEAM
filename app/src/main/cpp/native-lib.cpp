#include <jni.h>
#include <opencv2/opencv.hpp>

using namespace cv;
using namespace std;

extern "C"
JNIEXPORT void JNICALL
Java_com_tuapp_opencv_MainActivity_processFrame(JNIEnv *env, jobject, jlong matAddr) {
    Mat &frame = *(Mat *) matAddr;

    Mat hsv, maskGreen, maskRed;
    cvtColor(frame, hsv, COLOR_BGR2HSV);

    // Definir rangos para el color verde
    Scalar lowerGreen(35, 100, 100);
    Scalar upperGreen(85, 255, 255);
    inRange(hsv, lowerGreen, upperGreen, maskGreen);

    // Definir rangos para el color rojo (dos rangos por la naturaleza del rojo en HSV)
    Scalar lowerRed1(0, 120, 70), upperRed1(10, 255, 255);
    Scalar lowerRed2(170, 120, 70), upperRed2(180, 255, 255);
    Mat maskRed1, maskRed2;
    inRange(hsv, lowerRed1, upperRed1, maskRed1);
    inRange(hsv, lowerRed2, upperRed2, maskRed2);
    maskRed = maskRed1 | maskRed2;

    // Contornos de los objetos detectados
    vector<vector<Point>> contoursGreen, contoursRed;
    findContours(maskGreen, contoursGreen, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);
    findContours(maskRed, contoursRed, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

    // Dibujar contornos
    for (const auto &contour : contoursGreen)
        drawContours(frame, vector<vector<Point>>{contour}, -1, Scalar(0, 255, 0), 3);
    for (const auto &contour : contoursRed)
        drawContours(frame, vector<vector<Point>>{contour}, -1, Scalar(0, 0, 255), 3);
}
