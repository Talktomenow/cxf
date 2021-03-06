/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package demo.jaxrs.server;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkException;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

import scala.Tuple2;


@Path("/")
public class StreamingService {
    private Executor executor = new ThreadPoolExecutor(5, 5, 0, TimeUnit.SECONDS,
                                                       new ArrayBlockingQueue<Runnable>(10));
    private JavaStreamingContext jssc;
    public StreamingService(SparkConf sparkConf) {
        jssc = new JavaStreamingContext(sparkConf, Durations.seconds(1));
    }
    
    @POST
    @Path("/stream")
    @Consumes("text/plain")
    @Produces("text/plain")
    public void getStream(@Suspended AsyncResponse async, InputStream is) {
        try {
            JavaReceiverInputDStream<String> receiverStream = 
                jssc.receiverStream(new InputStreamReceiver(is));
            SparkStreamingOutput streamOut = new SparkStreamingOutput(jssc, 
                                                                createOutputDStream(receiverStream));
            jssc.addStreamingListener(new SparkStreamingListener(streamOut));
                                        
            executor.execute(new Runnable() {
                public void run() {
                     async.resume(streamOut);
                }
            });
        } catch (Exception ex) {
            // the compiler does not allow to catch SparkException directly
            if (ex instanceof SparkException) {
                async.cancel(60);
            } else {
                async.resume(new WebApplicationException(ex));
            }
        }
    }

    @SuppressWarnings("serial")
    private static JavaPairDStream<String, Integer> createOutputDStream(
            JavaReceiverInputDStream<String> receiverStream) {
        final JavaDStream<String> words = receiverStream.flatMap(
            new FlatMapFunction<String, String>() {
                @Override 
                public Iterator<String> call(String x) {
                    return Arrays.asList(x.split(" ")).iterator();
                }
            });
        final JavaPairDStream<String, Integer> pairs = words.mapToPair(
            new PairFunction<String, String, Integer>() {
            
                @Override 
                public Tuple2<String, Integer> call(String s) {
                    return new Tuple2<String, Integer>(s, 1);
                }
            });
        return pairs.reduceByKey(
            new Function2<Integer, Integer, Integer>() {
             
                @Override 
                public Integer call(Integer i1, Integer i2) {
                    return i1 + i2;
                }
            });
    }
   
    
}
