/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.stuy;

import com.googlecode.javacv.CanvasFrame;
import com.googlecode.javacv.cpp.opencv_core;
import com.googlecode.javacv.cpp.opencv_core.*;
import com.googlecode.javacv.cpp.opencv_imgproc;
import com.googlecode.javacv.cpp.opencv_imgproc.*;
import edu.wpi.first.wpijavacv.DaisyExtensions;
import edu.wpi.first.wpijavacv.WPIBinaryImage;
import edu.wpi.first.wpijavacv.WPIColor;
import edu.wpi.first.wpijavacv.WPIColorImage;
import edu.wpi.first.wpijavacv.WPIContour;
import edu.wpi.first.wpijavacv.WPIImage;
import edu.wpi.first.wpijavacv.WPIPoint;
import edu.wpi.first.wpijavacv.WPIPolygon;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Scanner;
import javax.imageio.ImageIO;


/* Much of this code is based on Team 341's DaisyCV code.
 * This is an implementation of their vision code using OpenCV on a Rasberry Pi.
 * This runs as a standalone command-line utility that will run when the Pi on the robot starts.
 */
/**
 *
 * @author yulli
 */
public class EyeInThePi {
    
    private WPIColor targetColor = new WPIColor(0, 255, 0);
    private static final NetworkIO network = new NetworkIO();

    // Constants that need to be tuned
    // TODO: Tune them.
    private static final double kNearlyHorizontalSlope = Math.tan(Math.toRadians(14));
    private static final double kNearlyVerticalSlope = Math.tan(Math.toRadians(90-15));
    private static final int kMinWidth = 20;
    private static final int kMaxWidth = 200;
    private static final int kHoleClosingIterations = 10;

    private static final double kShooterOffsetDeg = -1.55;
    private static final double kHorizontalFOVDeg = 47.0;
    private static final double kVerticalFOVDeg = 48.0;
    
    private static final double kCameraHeightIn = 54.0;
    private static final double kCameraPitchDeg = 21.0;
    private static final double kTopTargetHeightIn = 98.0 + 2.0 + 9.0; // 98 to rim, +2 to bottom of target, +9 to center of target

    private static boolean m_debugMode = true;

    // Store JavaCV temporaries as members to reduce memory management during processing
    private CvSize size = null;
    private WPIContour[] contours;
    private ArrayList<WPIPolygon> polygons;
    private IplConvKernel morphKernel;
    private IplImage bin;
    private IplImage hsv;
    private IplImage hue;
    private IplImage sat;
    private IplImage combined;
    private IplImage lightness;
    private IplImage logFiltered;
    private WPIPoint linePt1;
    private WPIPoint linePt2;
    private int horizontalOffsetPixels;
    
    public EyeInThePi()
    {
        this(false);
    }
    
    public EyeInThePi(boolean debug)
    {
        m_debugMode = debug;
        morphKernel = IplConvKernel.create(3, 3, 1, 1, opencv_imgproc.CV_SHAPE_RECT, null);

        //network = new NetworkIO();

        DaisyExtensions.init();
        
    }
    /* 
     * Takes a raw image, processes it, finds rectangles, and sends data about the best fit to the robot.
     */
    public WPIImage processImage(WPIColorImage rawImage)
    {
        double heading = 0.0; //TODO: Get this from the robot.
        
        if( size == null || size.width() != rawImage.getWidth() || size.height() != rawImage.getHeight() )
        {
            size = opencv_core.cvSize(rawImage.getWidth(),rawImage.getHeight());
            ImageFilters.init(size); // Tell ImageFilters the size of the image it'll be working with.
            bin = IplImage.create(size, 8, 1);
            hsv = IplImage.create(size, 8, 3);
            hue = IplImage.create(size, 8, 1);
            combined = IplImage.create(size, 8, 1);
            sat = IplImage.create(size, 8, 1);
            lightness = IplImage.create(size, 8, 1);
            logFiltered = IplImage.create(size, 8, 1);
            horizontalOffsetPixels =  (int)Math.round(kShooterOffsetDeg*(size.width()/kHorizontalFOVDeg));
            linePt1 = new WPIPoint(size.width()/2+horizontalOffsetPixels,size.height()-1);
            linePt2 = new WPIPoint(size.width()/2+horizontalOffsetPixels,0);
        }
        

        // Get the raw IplImages for OpenCV
        IplImage input = DaisyExtensions.getIplImage(rawImage);

	//logFiltered = ImageFilters.logFilter(input);


        // Convert to HSV color space
        opencv_imgproc.cvCvtColor(input, hsv, opencv_imgproc.CV_BGR2HLS);
        opencv_core.cvSplit(hsv, hue, lightness, sat, null);

        // Threshold each component separately
        
        combined = ImageFilters.hueFilter(hue);

        //opencv_core.cvNot(combined, combined);

        //opencv_core.cvNot(bin, bin);
        //opencv_core.cvNot(hue, hue);

        // Saturation
        opencv_imgproc.cvThreshold(sat, sat, 250, 255, opencv_imgproc.CV_THRESH_BINARY);

        // Value
        opencv_imgproc.cvThreshold(lightness, lightness, 150, 255, opencv_imgproc.CV_THRESH_BINARY);

        // Copy the combined hue image into bin
        opencv_core.cvCopy(combined, bin);

        
        // Combine images with bitwise operations
        opencv_core.cvOr(logFiltered, bin, bin, null);
        opencv_core.cvOr(bin, sat, bin, null);
        opencv_core.cvAnd(bin, lightness, bin, null);


        // Fill in any gaps using binary morphology
        opencv_imgproc.cvMorphologyEx(bin, bin, null, morphKernel, opencv_imgproc.CV_MOP_CLOSE, kHoleClosingIterations);

        // Find contours
        WPIBinaryImage binWpi = DaisyExtensions.makeWPIBinaryImage(bin);
        contours = DaisyExtensions.findConvexContours(binWpi);

        polygons = new ArrayList<WPIPolygon>();
        for (WPIContour c : contours)
        {
            //System.out.println("Contour: X: " + c.getX() + " Y: " + c.getY());
            rawImage.drawPoint(new WPIPoint(c.getX(), c.getY()), WPIColor.BLUE, 5);
            double ratio = ((double) c.getHeight()) / ((double) c.getWidth());
            // TODO: change magic numbers to match new targets sizes in 2013
            if (ratio < 10.0 && ratio > 0.0 && c.getWidth() > kMinWidth && c.getWidth() < kMaxWidth)
            {
                WPIPolygon p = c.approxPolygon(20);
                if (p.isConvex() && p.getNumVertices() == 4)
                {
                    //System.out.println("Ratio: " + ratio);
                }
                polygons.add(c.approxPolygon(20));
            }
        }

        WPIPolygon square = null;
        int highest = Integer.MAX_VALUE;

        for (WPIPolygon p : polygons)
        {
            if (p.isConvex() && p.getNumVertices() == 4)
            {

                // We passed the first test...we fit a rectangle to the polygon
                // Now do some more tests

                WPIPoint[] points = p.getPoints();
                // We expect to see a top line that is nearly horizontal, and two side lines that are nearly vertical
                int numNearlyHorizontal = 0;
                int numNearlyVertical = 0;
                for( int i = 0; i < 4; i++ )
                {
                    double dy = points[i].getY() - points[(i+1) % 4].getY();
                    double dx = points[i].getX() - points[(i+1) % 4].getX();
                    double slope = Double.MAX_VALUE;
                    if( dx != 0 ) {
                        slope = Math.abs(dy/dx);
                    }

                    if( slope < kNearlyHorizontalSlope ) {
                        ++numNearlyHorizontal;
                    }
                    else if( slope > kNearlyVerticalSlope ) {
                        ++numNearlyVertical;
                    }
                }

                if(numNearlyHorizontal >= 1 && numNearlyVertical == 2)
                {
                    rawImage.drawPolygon(p, WPIColor.BLUE, 2);

                    int pCenterX = (p.getX() + (p.getWidth() / 2));
                    int pCenterY = (p.getY() + (p.getHeight() / 2));

                    rawImage.drawPoint(new WPIPoint(pCenterX, pCenterY), targetColor, 5);
                    if (pCenterY < highest) // Because coord system is funny
                    {
                        square = p;
                        highest = pCenterY;
                    }
                }
            }
            else
            {
                rawImage.drawPolygon(p, WPIColor.YELLOW, 1);
            }
        }

        if (square != null)
        {
            int x = square.getX() + (square.getWidth() / 2);
            x = (2 * (x / size.width())) - 1;
            int y = square.getY() + (square.getHeight() / 2);
            y = -((2 * (y / size.height())) - 1);

            // Get the coordinates of the center of the square
            int squareCenterX = square.getX() + (square.getWidth() / 2);
            int squareCenterY = square.getY() + (square.getWidth() / 2);

            // Normalize them to be in a coordinate system with the center as (0,0)
            squareCenterX -= (size.width() / 2);
            squareCenterY -= (size.height() / 2);
            
            double degreesPerVerticalPixel = kVerticalFOVDeg / size.height();   // Find the number of degrees each pixel represents
            double verticalDegreesOff = -1 * squareCenterY * degreesPerVerticalPixel; // Find how far we are based on that
            
            System.out.println("Center: (" + squareCenterX + ", " + squareCenterY + ")");
            System.out.println("Off by " + Math.round(verticalDegreesOff) + " degrees.");
            network.setMostRecent(verticalDegreesOff);
            
            rawImage.drawPolygon(square, targetColor, 7);
        } 
        
        // Draw a crosshair
        rawImage.drawLine(linePt1, linePt2, targetColor, 2);

        DaisyExtensions.releaseMemory();

        //System.gc();
        
        return rawImage;
    }
    
    private double boundAngle0to360Degrees(double angle)
    {
        // Naive algorithm
        while(angle >= 360.0)
        {
            angle -= 360.0;
        }
        while(angle < 0.0)
        {
            angle += 360.0;
        }
        return angle;
    }
    
    
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args)
    {
        
        //new DashboardFrame(!m_debugMode); //Call the constructor for DashboardFrame, because FIRST is stupid.
        // Create the PiEye
        EyeInThePi pieye = new EyeInThePi(true);
        Camera cam = new Camera();

        Thread t = new Thread(
                new Runnable () {
                    public void run () {
                        while (true) { 
                            network.run();
                        }
                    }
                });
        t.start();

        boolean running = true;
        
        long totalTime = 0;
        
        while (running) {
            // Load the image
            WPIColorImage rawImage;
            try
            {
                    long startTime, endTime;
                    startTime = System.nanoTime();
                rawImage = cam.getFrame();//new WPIColorImage(ImageIO.read(new File(args[i%args.length])));
                if (rawImage != null) {
                    WPIImage resultImage;

                    // Process image
                    resultImage = pieye.processImage(rawImage);
                    endTime = System.nanoTime();

                    
                    // Display results
                    totalTime += (endTime - startTime);
                    double milliseconds = (double) (endTime - startTime) / 1000000.0;
                    System.out.format("Processing took %.2f milliseconds%n", milliseconds);
                    System.out.format("(%.2f frames per second)%n", 1000.0 / milliseconds);
                
                }

            } catch (Exception e) {
                e.printStackTrace();
                //System.out.println("Waiting for camera -- Give it a minute");
            }
        }

        System.exit(0);
    }
}
