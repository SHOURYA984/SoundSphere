package com.soundsphere.util;
import com.google.gson.Gson;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
public final class Json {
 public static final Gson G=new Gson(); private Json(){}
 public static Map<String,Object> body(HttpServletRequest r)throws IOException{
  try(Reader rd=r.getReader()){Map<?,?> m=G.fromJson(rd,Map.class);return m==null?new HashMap<>():new HashMap<>((Map<String,Object>)m);}
 }
 public static void send(HttpServletResponse r,int status,Object obj)throws IOException{
  r.setStatus(status);r.setContentType("application/json;charset=UTF-8");G.toJson(obj,r.getWriter());
 }
 public static Map<String,Object> obj(Object... kv){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
 public static String str(Map<String,Object> m,String k){Object v=m.get(k);return v==null?"":String.valueOf(v).trim();}
 public static long num(Map<String,Object> m,String k){try{return (long)Double.parseDouble(String.valueOf(m.get(k)));}catch(Exception e){return 0;}}
}
